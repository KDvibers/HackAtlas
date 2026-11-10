package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

internal enum class RecoveryStep { CLOSED, EMAIL, CODE, PASSWORD, COMPLETE, UNCERTAIN, UNAVAILABLE }
internal enum class RecoveryNotice { CODE_EXPIRED, GRANT_EXPIRED, NEW_CODE_REQUIRED }

internal data class PasswordRecoveryState(
    val step: RecoveryStep = RecoveryStep.CLOSED,
    val email: String = "",
    val code: String = "",
    val password: String = "",
    val busy: PasswordResetOperation? = null,
    val resendSeconds: Int = 0,
    val retrySeconds: Int = 0,
    val proofSeconds: Int = 0,
    val notice: RecoveryNotice? = null,
    val error: AuthError? = null,
) {
    val open get() = step != RecoveryStep.CLOSED
    val canRequest get() = (step == RecoveryStep.EMAIL || step == RecoveryStep.CODE) &&
        busy == null && resendSeconds == 0 && isPlausibleAuthEmail(email)
    val canVerify get() = step == RecoveryStep.CODE && busy == null && retrySeconds == 0 &&
        proofSeconds > 0 && EMAIL_CODE.matches(code)
    val canReset get() = step == RecoveryStep.PASSWORD && busy == null && retrySeconds == 0 &&
        proofSeconds > 0 && contractTextLength(password, allowNul = true) in 12..256

    override fun toString() = "PasswordRecoveryState([REDACTED])"
}

/** Process memory only. Each form lifetime owns its requests and proofs for one fixed origin. */
class PasswordRecoveryStore internal constructor(
    private val client: N2AccountClient,
    private val auth: AuthStore,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val mutableState = MutableStateFlow(PasswordRecoveryState())
    internal val state = mutableState.asStateFlow()
    private var generation = 0L
    private var challenge: EmailActionChallenge? = null
    private var grant: PasswordResetGrant? = null
    private var resendAt: Instant? = null
    private var proofRetryAt: Instant? = null
    private var incompatible = false
    private var operation: Job? = null
    private var ticker: Job? = null

    init {
        scope.launch {
            auth.state.collect {
                if (state.value.open && !auth.canRecoverPassword()) close()
            }
        }
    }

    internal fun open(email: String): Boolean {
        if (!auth.canRecoverPassword()) return false
        close()
        mutableState.value = PasswordRecoveryState(
            step = if (incompatible) RecoveryStep.UNAVAILABLE else RecoveryStep.EMAIL,
            email = email.trim(),
        )
        refreshState()
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                refreshState()
            }
        }
        return true
    }

    internal fun close() {
        invalidate()
        ticker?.cancel()
        ticker = null
        challenge = null
        grant = null
        mutableState.value = PasswordRecoveryState()
    }

    internal fun onBackground() {
        val current = state.value
        if (!current.open) return
        invalidate()
        grant = null
        when {
            current.busy == PasswordResetOperation.CONFIRM -> {
                challenge = null
                mutableState.value = PasswordRecoveryState(step = RecoveryStep.UNCERTAIN, email = current.email)
            }
            current.busy == PasswordResetOperation.VERIFY || current.step == RecoveryStep.PASSWORD ->
                returnToEmail(RecoveryNotice.NEW_CODE_REQUIRED)
            else -> mutableState.value = current.copy(code = "", password = "", busy = null)
        }
        refreshState()
    }

    internal fun restart() {
        val current = state.value
        if (!current.open || current.busy != null || incompatible || !auth.canRecoverPassword()) return
        invalidate()
        returnToEmail()
        refreshState()
    }

    internal fun changeEmail(value: String) {
        val current = state.value
        if (current.step == RecoveryStep.EMAIL && current.busy == null)
            mutableState.value = current.copy(email = value, error = null)
    }

    internal fun changeCode(value: String) {
        val current = state.value
        if (current.step == RecoveryStep.CODE && current.busy == null &&
            value.length <= 8 && value.all { it in '0'..'9' })
            mutableState.value = current.copy(code = value, error = null)
    }

    internal fun changePassword(value: String) {
        val current = state.value
        if (current.step == RecoveryStep.PASSWORD && current.busy == null)
            mutableState.value = current.copy(password = value, error = null)
    }

    internal fun requestCode() {
        refreshState()
        if (!state.value.canRequest || !auth.canRecoverPassword()) return
        val email = state.value.email.trim()
        runOperation(PasswordResetOperation.REQUEST) { attempt ->
            val accepted = client.requestPasswordReset(email)
            if (isCurrent(attempt)) {
                waitBefore(PasswordResetOperation.REQUEST, accepted.resendAfterSeconds)
                challenge = accepted
                mutableState.value = PasswordRecoveryState(step = RecoveryStep.CODE, email = email,
                    busy = PasswordResetOperation.REQUEST)
            }
        }
    }

    internal fun verifyCode() {
        refreshState()
        val current = state.value
        val proof = challenge ?: return
        if (!current.canVerify || !auth.canRecoverPassword()) return
        var code = current.code
        mutableState.value = current.copy(code = "")
        runOperation(PasswordResetOperation.VERIFY) { attempt ->
            val received = try { client.verifyPasswordReset(proof, code) } finally { code = "" }
            if (isCurrent(attempt)) {
                challenge = null
                grant = received
                mutableState.value = PasswordRecoveryState(step = RecoveryStep.PASSWORD, email = state.value.email,
                    busy = PasswordResetOperation.VERIFY)
            }
        }
    }

    internal fun resetPassword() {
        refreshState()
        val current = state.value
        val proof = grant ?: return
        if (!current.canReset || !auth.canRecoverPassword()) return
        var password = current.password
        mutableState.value = current.copy(password = "")
        runOperation(PasswordResetOperation.CONFIRM) { attempt ->
            try { client.confirmPasswordReset(proof, password) } finally { password = "" }
            if (isCurrent(attempt)) {
                grant = null
                challenge = null
                mutableState.value = PasswordRecoveryState(step = RecoveryStep.COMPLETE)
            }
        }
    }

    private fun runOperation(action: PasswordResetOperation, block: suspend (Long) -> Unit) {
        val attempt = generation
        mutableState.value = state.value.copy(busy = action, notice = null, error = null)
        operation = scope.launch {
            try {
                block(attempt)
            } catch (failure: AuthException) {
                if (isCurrent(attempt)) handleFailure(action, failure.error)
            } catch (_: RecoveryProofExpired) {
                if (isCurrent(attempt)) returnToEmail(expiryNotice(action))
            } finally {
                if (isCurrent(attempt)) {
                    mutableState.value = state.value.copy(busy = null)
                    refreshState()
                }
            }
        }
    }

    private fun handleFailure(action: PasswordResetOperation, error: AuthError) {
        val current = state.value
        val http = error as? AuthError.Http
        when {
            error == AuthError.UnsupportedServer -> {
                incompatible = true
                challenge = null
                grant = null
                mutableState.value = PasswordRecoveryState(step = RecoveryStep.UNAVAILABLE, error = error)
            }
            http?.retryAfterSeconds != null -> {
                waitBefore(action, http.retryAfterSeconds)
                mutableState.value = current.copy(error = error)
            }
            action == PasswordResetOperation.CONFIRM && error.outcomeUnknown() -> {
                grant = null
                mutableState.value = PasswordRecoveryState(step = RecoveryStep.UNCERTAIN, email = current.email, error = error)
            }
            action == PasswordResetOperation.CONFIRM && http?.n2Action == N2HttpAction.INVALID_EMAIL_ACTION ->
                returnToEmail(RecoveryNotice.NEW_CODE_REQUIRED, error)
            action == PasswordResetOperation.VERIFY && error.outcomeUnknown() ->
                returnToEmail(RecoveryNotice.NEW_CODE_REQUIRED, error)
            else -> {
                if (action == PasswordResetOperation.REQUEST && error.outcomeUnknown())
                    waitBefore(action, EMAIL_RESEND_SECONDS)
                mutableState.value = current.copy(error = error)
            }
        }
    }

    private fun invalidate() {
        if (state.value.busy == PasswordResetOperation.REQUEST)
            waitBefore(PasswordResetOperation.REQUEST, EMAIL_RESEND_SECONDS)
        generation++
        operation?.cancel()
        operation = null
    }

    private fun isCurrent(attempt: Long): Boolean =
        attempt == generation && state.value.open && auth.canRecoverPassword()

    private fun waitBefore(action: PasswordResetOperation, seconds: Int) {
        val until = now() + seconds.seconds
        if (action == PasswordResetOperation.REQUEST) {
            if (resendAt?.let { it >= until } != true) resendAt = until
        } else {
            // Verify and confirm share one server quota.
            if (proofRetryAt?.let { it >= until } != true) proofRetryAt = until
        }
    }

    private fun returnToEmail(notice: RecoveryNotice? = null, error: AuthError? = null) {
        challenge = null
        grant = null
        mutableState.value = PasswordRecoveryState(step = RecoveryStep.EMAIL, email = state.value.email,
            notice = notice, error = error)
    }

    private fun refreshState() {
        var current = state.value
        if (!current.open) return
        val clock = now()
        val deadline = when (current.step) {
            RecoveryStep.CODE -> challenge?.expiresAt
            RecoveryStep.PASSWORD -> grant?.expiresAt
            else -> null
        }
        val activeOperation = when (current.step) {
            RecoveryStep.CODE -> PasswordResetOperation.VERIFY
            RecoveryStep.PASSWORD -> PasswordResetOperation.CONFIRM
            else -> PasswordResetOperation.REQUEST
        }
        if (deadline != null && deadline <= clock && current.busy == null) {
            returnToEmail(expiryNotice(activeOperation))
            current = state.value
        }
        mutableState.value = current.copy(
            proofSeconds = secondsLeft(deadline, clock),
            resendSeconds = secondsLeft(resendAt, clock),
            retrySeconds = if (current.step == RecoveryStep.CODE || current.step == RecoveryStep.PASSWORD)
                secondsLeft(proofRetryAt, clock) else 0,
        )
    }

    private fun expiryNotice(action: PasswordResetOperation) =
        if (action == PasswordResetOperation.CONFIRM) RecoveryNotice.GRANT_EXPIRED else RecoveryNotice.CODE_EXPIRED
}
