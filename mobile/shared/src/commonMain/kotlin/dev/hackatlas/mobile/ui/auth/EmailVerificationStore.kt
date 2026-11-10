package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

internal enum class VerificationOperation { REQUEST, CONFIRM, CHECK }

internal data class EmailVerificationState(
    val owner: LocalSessionIdentity? = null,
    val challenge: EmailActionChallenge? = null,
    val code: String = "",
    val pending: Boolean = false,
    val busy: VerificationOperation? = null,
    val resendSeconds: Int = 0,
    val confirmSeconds: Int = 0,
    val challengeSeconds: Int = 0,
    val needsProfileCheck: Boolean = false,
    val challengeExpired: Boolean = false,
    val profileStillPending: Boolean = false,
    val error: AuthError? = null,
) {
    val canRequest get() = pending && error != AuthError.UnsupportedServer && busy == null &&
        !needsProfileCheck && resendSeconds == 0
    val canConfirm get() = pending && error != AuthError.UnsupportedServer && busy == null &&
        !needsProfileCheck && confirmSeconds == 0 &&
        challenge != null && EMAIL_CODE.matches(code)

    override fun toString() = "EmailVerificationState([REDACTED])"
}

/** Main-thread coordinator. A closed form discards the proof but retains same-session retry guards. */
class EmailVerificationStore internal constructor(
    private val client: N2AccountClient,
    private val session: AuthSessionClient,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val mutableState = MutableStateFlow(EmailVerificationState())
    internal val state = mutableState.asStateFlow()
    private var owner: LocalSessionIdentity? = null
    private var resendAt: Instant? = null
    private var confirmAt: Instant? = null
    private var needsProfileCheck = false
    private var unsupported = false
    private var operation: Job? = null
    private var ticker: Job? = null
    private var flowRevision = 0L

    init {
        scope.launch {
            session.view.collect { view ->
                if (owner != null && (view.identity != owner || view.access?.canRead == true)) {
                    close()
                    owner = null
                    resendAt = null
                    confirmAt = null
                    needsProfileCheck = false
                    unsupported = false
                } else {
                    refreshState()
                }
            }
        }
    }

    internal fun open(identity: LocalSessionIdentity): Boolean {
        val access = session.currentPrivateAccess() ?: return false
        if (access.identity != identity || access.trust != PrivateTrust.PENDING) return false
        if (state.value.owner == identity) return true
        close()
        if (owner != identity) {
            owner = identity
            resendAt = null
            confirmAt = null
            needsProfileCheck = false
            unsupported = false
        }
        mutableState.value = EmailVerificationState(
            owner = identity,
            error = AuthError.UnsupportedServer.takeIf { unsupported },
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
        if (state.value.busy == VerificationOperation.REQUEST) setResendDelay(EMAIL_RESEND_SECONDS)
        flowRevision++
        operation?.cancel()
        ticker?.cancel()
        mutableState.value = EmailVerificationState()
    }

    internal fun onBackground() {
        mutableState.value = state.value.copy(code = "")
    }

    internal fun changeCode(value: String) {
        refreshState()
        val current = state.value
        if (current.pending && current.challenge != null && current.busy == null && !current.needsProfileCheck &&
            value.length <= 8 && value.all { it in '0'..'9' }) {
            mutableState.value = current.copy(code = value, error = null, profileStillPending = false)
        }
    }

    internal fun requestCode() {
        refreshState()
        val current = state.value
        val identity = current.owner ?: return
        if (!current.canRequest) return
        runOperation(VerificationOperation.REQUEST) { revision ->
            try {
                when (val result = client.requestVerification(identity)) {
                    is VerificationRequestResult.Accepted -> if (isCurrent(identity, revision)) {
                        setResendDelay(result.challenge.resendAfterSeconds)
                        mutableState.value = state.value.copy(challenge = result.challenge, code = "", challengeExpired = false)
                    }
                    VerificationRequestResult.AlreadyVerified -> if (isCurrent(identity, revision)) {
                        needsProfileCheck = true
                        mutableState.value = state.value.copy(challenge = null, code = "")
                        reconcile(identity, revision)
                    }
                }
            } catch (failure: AuthException) {
                if (isCurrent(identity, revision)) {
                    val retry = (failure.error as? AuthError.Http)?.retryAfterSeconds
                    if (retry != null) setResendDelay(retry)
                    else if (failure.error.outcomeUnknown()) setResendDelay(EMAIL_RESEND_SECONDS)
                    showFailure(failure.error)
                }
            }
        }
    }

    internal fun confirmCode() {
        refreshState()
        val current = state.value
        val identity = current.owner ?: return
        val challenge = current.challenge ?: return
        if (!current.canConfirm) return
        var submittedCode = current.code
        needsProfileCheck = true
        mutableState.value = current.copy(code = "", needsProfileCheck = true)
        runOperation(VerificationOperation.CONFIRM) { revision ->
            try {
                try {
                    client.confirmVerification(identity, challenge, submittedCode)
                } finally {
                    submittedCode = ""
                }
                if (isCurrent(identity, revision)) mutableState.value = state.value.copy(challenge = null)
            } catch (_: VerificationChallengeExpired) {
                if (isCurrent(identity, revision)) {
                    needsProfileCheck = false
                    mutableState.value = state.value.copy(challenge = null, challengeExpired = true)
                }
                return@runOperation
            } catch (failure: AuthException) {
                if (!isCurrent(identity, revision)) return@runOperation
                if (!failure.error.outcomeUnknown()) {
                    needsProfileCheck = false
                    confirmAt = (failure.error as? AuthError.Http)?.retryAfterSeconds?.let { now() + it.seconds }
                    showFailure(failure.error)
                    return@runOperation
                }
            }
            if (isCurrent(identity, revision)) reconcile(identity, revision)
        }
    }

    internal fun checkProfile() {
        refreshState()
        val current = state.value
        val identity = current.owner ?: return
        if (unsupported || current.busy != null || !current.needsProfileCheck && current.pending) return
        needsProfileCheck = true
        runOperation(VerificationOperation.CHECK) { revision -> reconcile(identity, revision) }
    }

    private suspend fun reconcile(identity: LocalSessionIdentity, revision: Long) {
        try {
            val verified = client.checkVerification(identity)
            if (isCurrent(identity, revision) && !verified) {
                needsProfileCheck = false
                mutableState.value = state.value.copy(profileStillPending = true)
            }
        } catch (failure: AuthException) {
            if (isCurrent(identity, revision)) showFailure(failure.error)
        }
    }

    private fun showFailure(error: AuthError) {
        if (error == AuthError.UnsupportedServer) {
            unsupported = true
            needsProfileCheck = false
            mutableState.value = state.value.copy(error = error, challenge = null, code = "")
        } else {
            mutableState.value = state.value.copy(error = error)
        }
    }

    private fun runOperation(action: VerificationOperation, block: suspend (Long) -> Unit) {
        val identity = state.value.owner ?: return
        val revision = flowRevision
        mutableState.value = state.value.copy(busy = action, error = null, profileStillPending = false)
        operation = scope.launch {
            try {
                block(revision)
            } catch (cancelled: CancellationException) {
                if (action == VerificationOperation.REQUEST && isCurrent(identity, revision))
                    setResendDelay(EMAIL_RESEND_SECONDS)
                throw cancelled
            } finally {
                if (isCurrent(identity, revision)) {
                    mutableState.value = state.value.copy(busy = null)
                    refreshState()
                }
            }
        }
    }

    private fun isCurrent(identity: LocalSessionIdentity, revision: Long): Boolean =
        revision == flowRevision && state.value.owner == identity && session.localIdentity.value == identity

    private fun setResendDelay(seconds: Int) {
        resendAt = now() + seconds.seconds
    }

    private fun refreshState() {
        val current = state.value
        if (current.owner == null) return
        val clock = now()
        val access = session.currentPrivateAccess()
        val challenge = current.challenge?.takeIf { it.expiresAt > clock }
        mutableState.value = current.copy(
            pending = access?.identity == current.owner && access?.trust == PrivateTrust.PENDING,
            challenge = challenge,
            code = current.code.takeIf { challenge != null }.orEmpty(),
            challengeExpired = current.challengeExpired || current.challenge != null && challenge == null,
            challengeSeconds = secondsLeft(challenge?.expiresAt, clock),
            resendSeconds = secondsLeft(resendAt, clock),
            confirmSeconds = secondsLeft(confirmAt, clock),
            needsProfileCheck = needsProfileCheck,
        )
    }
}

private fun secondsLeft(until: Instant?, now: Instant): Int {
    if (until == null || until <= now) return 0
    val remaining = until - now
    val seconds = remaining.inWholeSeconds
    val rounded = if (remaining > seconds.seconds && seconds < Int.MAX_VALUE) seconds + 1 else seconds
    return rounded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private fun AuthError.outcomeUnknown(): Boolean =
    this == AuthError.Network || this == AuthError.Timeout || this == AuthError.InvalidResponse ||
        this is AuthError.Http && status >= 500
