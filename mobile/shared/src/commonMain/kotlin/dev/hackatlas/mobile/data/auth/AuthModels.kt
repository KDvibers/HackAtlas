package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ProfileDto
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
class StoredSession(
    val accessToken: String, val expiresAt: String, val userId: String,
    val generation: String? = null, val cleanupRequired: Boolean = false,
) {
    override fun toString() = "StoredSession([REDACTED])"
}

enum class AuthProtocol { LEGACY, CLOSED_N2 }

enum class EmailVerification { PENDING, VERIFIED }

sealed interface AuthState {
    data object NotLoaded : AuthState
    data object SignedOut : AuthState
    data object SignInRequired : AuthState
    data object StorageUnavailable : AuthState
    class Authenticated(val user: ProfileDto, val emailVerification: EmailVerification? = null) : AuthState {
        override fun toString() = "Authenticated"
    }
}

enum class N2HttpAction {
    INVALID_CREDENTIALS, EMAIL_NOT_VERIFIED, PASSWORD_RESET_REQUIRED, FORBIDDEN, INVALID_INPUT,
    INVALID_EMAIL_ACTION, EMAIL_ALREADY_EXISTS, NOT_FOUND, RATE_LIMITED, SERVER_ERROR, RETRY_MANUALLY,
}

sealed interface AuthError {
    data object Unauthorized : AuthError
    data object Storage : AuthError
    data object Network : AuthError
    data object Timeout : AuthError
    data object InvalidResponse : AuthError
    data object UnsupportedServer : AuthError
    data class Http(
        val status: Int,
        val retryAfterSeconds: Int?,
        val n2Action: N2HttpAction? = null,
        val requestId: String? = null,
    ) : AuthError {
        override fun toString() = "Http(status=$status, retryAfterSeconds=$retryAfterSeconds, n2Action=$n2Action)"
    }
}

class AuthException(val error: AuthError) : Exception("Auth request failed: $error")
class SecureStorageException : Exception("Secure session storage unavailable")

/** Locally validated credential metadata, never proof of current server acceptance. */
data class LocalSessionIdentity(
    val userId: String, val expiresAt: String,
    val apiBaseUrl: String? = null, val generation: String? = null,
) {
    override fun toString() = "LocalSessionIdentity([REDACTED])"
}

enum class PrivateTrust { UNKNOWN, PENDING, VERIFIED_ONLINE, VERIFIED_OFFLINE }

/** Only present in the explicitly selected N2 runtime. Revision closes work on a trust change. */
@ConsistentCopyVisibility
data class PrivateAccess internal constructor(
    val identity: LocalSessionIdentity? = null,
    val trust: PrivateTrust = PrivateTrust.UNKNOWN,
    internal val revision: Long = 0,
) {
    val canRead get() = trust == PrivateTrust.VERIFIED_ONLINE || trust == PrivateTrust.VERIFIED_OFFLINE
    val canWrite get() = trust == PrivateTrust.VERIFIED_ONLINE
}

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
    require(generation == null || USER_ID.matches(generation))
    require(!cleanupRequired || generation != null)
    Instant.parse(expiresAt)
}
