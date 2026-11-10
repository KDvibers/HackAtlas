package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ContractJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val EMAIL_RESEND_SECONDS = 60
internal val EMAIL_CODE = Regex("[0-9]{8}")
private val EMAIL_ACTION_TOKEN = Regex("[A-Za-z0-9_-]{43}")
internal class VerificationChallengeExpired : Exception("Verification challenge expired")
internal class RecoveryProofExpired : Exception("Password recovery proof expired")

internal class EmailActionChallenge(
    val id: String,
    val expiresAt: Instant,
    val resendAfterSeconds: Int,
) {
    override fun toString() = "EmailActionChallenge([REDACTED])"
}

internal class PasswordResetGrant(val token: String, val expiresAt: Instant) {
    override fun toString() = "PasswordResetGrant([REDACTED])"
}

internal enum class PasswordResetOperation(val path: String, val status: Int) {
    REQUEST("auth/password-reset/request", 202),
    VERIFY("auth/password-reset/verify", 200),
    CONFIRM("auth/password-reset/confirm", 204),
}

internal sealed interface VerificationRequestResult {
    data class Accepted(val challenge: EmailActionChallenge) : VerificationRequestResult
    data object AlreadyVerified : VerificationRequestResult
}

internal class N2AccountClient(private val session: AuthSessionClient) {
    init { require(session.protocol == AuthProtocol.CLOSED_N2) }

    suspend fun requestVerification(owner: LocalSessionIdentity): VerificationRequestResult =
        session.emailVerification(owner, proof = null) { response ->
            if (response.status.value == 204) {
                response.requireEmptyEmailActionBody()
                VerificationRequestResult.AlreadyVerified
            } else {
                VerificationRequestResult.Accepted(response.readChallenge())
            }
        }

    suspend fun confirmVerification(owner: LocalSessionIdentity, challenge: EmailActionChallenge, code: String) {
        require(EMAIL_CODE.matches(code))
        require(EMAIL_ACTION_TOKEN.matches(challenge.id))
        session.emailVerification(owner, EmailCodeProof(challenge.id, code), challenge.expiresAt) {
            it.requireEmptyEmailActionBody()
        }
    }

    suspend fun checkVerification(owner: LocalSessionIdentity): Boolean = session.checkVerification(owner)

    suspend fun requestPasswordReset(email: String): EmailActionChallenge {
        require(contractTextLength(email) in 1..254)
        return session.passwordReset(PasswordResetOperation.REQUEST, emailActionPostBody(PasswordResetEmail(email))) {
            it.readChallenge()
        }
    }

    suspend fun verifyPasswordReset(challenge: EmailActionChallenge, code: String): PasswordResetGrant {
        require(EMAIL_CODE.matches(code))
        require(EMAIL_ACTION_TOKEN.matches(challenge.id))
        return session.passwordReset(
            PasswordResetOperation.VERIFY,
            emailActionPostBody(EmailCodeProof(challenge.id, code)),
            challenge.expiresAt,
        ) { it.readResetGrant() }
    }

    suspend fun confirmPasswordReset(grant: PasswordResetGrant, newPassword: String) {
        require(EMAIL_ACTION_TOKEN.matches(grant.token))
        require(contractTextLength(newPassword, allowNul = true) in 12..256)
        session.passwordReset(
            PasswordResetOperation.CONFIRM,
            emailActionPostBody(PasswordResetConfirmation(grant.token, newPassword)),
            grant.expiresAt,
        ) { it.requireEmptyEmailActionBody() }
    }
}

@Serializable
internal class EmailCodeProof(val challengeId: String, val code: String) {
    override fun toString() = "EmailCodeProof([REDACTED])"
}

@Serializable
private class PasswordResetEmail(val email: String) {
    override fun toString() = "PasswordResetEmail([REDACTED])"
}

@Serializable
private class PasswordResetConfirmation(val resetToken: String, val newPassword: String) {
    override fun toString() = "PasswordResetConfirmation([REDACTED])"
}

/** Ktor's OkHttp engine treats channel content as one-shot, including the empty request POST. */
internal inline fun <reified T : Any> emailActionPostBody(payload: T?): OutgoingContent {
    val bytes = payload?.let { ContractJson.encodeToString(it).encodeToByteArray() } ?: byteArrayOf()
    val mediaType = ContentType.Application.Json.takeIf { payload != null }
    return object : OutgoingContent.ReadChannelContent() {
        override val contentType = mediaType
        override val contentLength = bytes.size.toLong()
        override fun readFrom() = ByteReadChannel(bytes)
    }
}

@Serializable
private class EmailActionChallengeDto(val challengeId: String, val expiresAt: String, val resendAfterSeconds: Int)

private suspend fun HttpResponse.readChallenge(): EmailActionChallenge = decodeEmailAction {
    val dto = ContractJson.decodeFromString<EmailActionChallengeDto>(bodyAsText())
    require(EMAIL_ACTION_TOKEN.matches(dto.challengeId))
    require(dto.resendAfterSeconds == EMAIL_RESEND_SECONDS)
    EmailActionChallenge(dto.challengeId, Instant.parse(dto.expiresAt), dto.resendAfterSeconds)
}

@Serializable
private class PasswordResetGrantDto(val resetToken: String, val expiresAt: String)

private suspend fun HttpResponse.readResetGrant(): PasswordResetGrant = decodeEmailAction {
    val dto = ContractJson.decodeFromString<PasswordResetGrantDto>(bodyAsText())
    require(EMAIL_ACTION_TOKEN.matches(dto.resetToken))
    PasswordResetGrant(dto.resetToken, Instant.parse(dto.expiresAt))
}

private inline fun <T> decodeEmailAction(read: () -> T): T = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw AuthException(AuthError.InvalidResponse)
}

private suspend fun HttpResponse.requireEmptyEmailActionBody() = decodeEmailAction {
    require(bodyAsText().isEmpty())
}
