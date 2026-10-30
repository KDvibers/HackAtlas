package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ProfileDto
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
class StoredSession(val accessToken: String, val expiresAt: String, val userId: String) {
    override fun toString() = "StoredSession([REDACTED])"
}

sealed interface AuthState {
    data object NotLoaded : AuthState
    data object SignedOut : AuthState
    data object SignInRequired : AuthState
    data object StorageUnavailable : AuthState
    class Authenticated(val user: ProfileDto) : AuthState { override fun toString() = "Authenticated" }
}

sealed interface AuthError {
    data object Unauthorized : AuthError
    data object Storage : AuthError
    data object Network : AuthError
    data object Timeout : AuthError
    data object InvalidResponse : AuthError
    data class Http(val status: Int, val retryAfterSeconds: Int?) : AuthError
}

class AuthException(val error: AuthError) : Exception("Auth request failed: $error")
class SecureStorageException : Exception("Secure session storage unavailable")

sealed interface LogoutResult {
    data object Revoked : LogoutResult
    data object AlreadyInvalid : LogoutResult
    data object NoLocalSession : LogoutResult
    data class LocalOnly(val error: AuthError) : LogoutResult
}

internal val USER_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
internal fun StoredSession.validate() {
    require(accessToken.length >= 32 && Regex("[A-Za-z0-9._~+/-]+=*").matches(accessToken))
    require(USER_ID.matches(userId))
    Instant.parse(expiresAt)
}
