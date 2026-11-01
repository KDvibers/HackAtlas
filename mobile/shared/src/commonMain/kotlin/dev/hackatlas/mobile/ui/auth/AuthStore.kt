package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class AuthOperation { RESTORE, LOGIN, REGISTER, LOGOUT }
enum class AuthNotice { LOGGED_OUT, SESSION_INVALID, LOCAL_ONLY_LOGOUT, DEVICE_LOGOUT }

data class AuthUiState(
    val session: AuthState = AuthState.NotLoaded,
    val busy: AuthOperation? = null,
    val error: AuthError? = null,
    val fields: AuthFormErrors = AuthFormErrors(),
    val notice: AuthNotice? = null,
    val formRevision: Long = 0,
) {
    val canAuthenticate get() = busy == null && (session == AuthState.SignedOut || session == AuthState.SignInRequired)
    override fun toString() = "AuthUiState(busy=$busy,error=$error,notice=$notice)"
}

/** Main-thread, process-owned coordinator. Never retains form values or touches guest storage. */
class AuthStore(private val client: AuthSessionClient, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state = mutableState.asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun onBackground() {
        mutableState.value = mutableState.value.copy(formRevision = mutableState.value.formRevision + 1)
    }

    fun onForeground() {
        if (!started) start()
        else if (mutableState.value.session is AuthState.Authenticated || mutableState.value.session == AuthState.NotLoaded) refresh()
    }

    fun refresh() {
        if (mutableState.value.busy != null) return
        started = true
        perform(AuthOperation.RESTORE) { client.restore() }
    }

    fun submit(mode: AuthFormMode, email: String, password: String, name: String): Boolean {
        if (!mutableState.value.canAuthenticate) return false
        val fields = validateAuthForm(mode, email, password, name)
        mutableState.value = mutableState.value.copy(fields = fields, error = null, notice = null)
        if (!fields.isEmpty()) return false
        perform(if (mode == AuthFormMode.LOGIN) AuthOperation.LOGIN else AuthOperation.REGISTER) {
            if (mode == AuthFormMode.LOGIN) client.login(email.trim(), password)
            else client.register(email.trim(), password, name.trim())
        }
        return true
    }

    fun logout() {
        if (mutableState.value.busy != null) return
        perform(AuthOperation.LOGOUT) {
            val notice = when (client.logout()) {
                LogoutResult.Revoked -> AuthNotice.LOGGED_OUT
                LogoutResult.AlreadyInvalid -> AuthNotice.SESSION_INVALID
                LogoutResult.NoLocalSession -> AuthNotice.DEVICE_LOGOUT
                is LogoutResult.LocalOnly -> AuthNotice.LOCAL_ONLY_LOGOUT
            }
            mutableState.value = mutableState.value.copy(notice = notice)
        }
    }

    fun dismissFeedback() {
        mutableState.value = mutableState.value.copy(error = null, fields = AuthFormErrors(), notice = null)
    }

    private fun perform(operation: AuthOperation, block: suspend () -> Unit) {
        val previous = mutableState.value
        mutableState.value = previous.copy(
            session = if (operation == AuthOperation.LOGOUT) AuthState.NotLoaded else previous.session,
            busy = operation, error = null, fields = AuthFormErrors(), notice = null,
        )
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: AuthException) {
                mutableState.value = mutableState.value.copy(error = failed.error)
            } finally {
                mutableState.value = mutableState.value.copy(session = client.state.value, busy = null)
            }
        }
    }
}
