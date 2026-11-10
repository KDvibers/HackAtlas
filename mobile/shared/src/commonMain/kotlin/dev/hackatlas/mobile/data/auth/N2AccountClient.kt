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
private val CHALLENGE_ID = Regex("[A-Za-z0-9_-]{43}")
internal class VerificationChallengeExpired : Exception("Verification challenge expired")

internal class EmailActionChallenge(
    val id: String,
    val expiresAt: Instant,
    val resendAfterSeconds: Int,
) {
    override fun toString() = "EmailActionChallenge([REDACTED])"
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
                response.requireEmptyVerificationBody()
                VerificationRequestResult.AlreadyVerified
            } else {
                VerificationRequestResult.Accepted(response.readChallenge())
            }
        }

    suspend fun confirmVerification(owner: LocalSessionIdentity, challenge: EmailActionChallenge, code: String) {
        require(EMAIL_CODE.matches(code))
        require(CHALLENGE_ID.matches(challenge.id))
        session.emailVerification(owner, EmailVerificationProof(challenge.id, code), challenge.expiresAt) {
            it.requireEmptyVerificationBody()
        }
    }

    suspend fun checkVerification(owner: LocalSessionIdentity): Boolean = session.checkVerification(owner)
}

@Serializable
internal class EmailVerificationProof(val challengeId: String, val code: String) {
    override fun toString() = "EmailVerificationProof([REDACTED])"
}

/** Ktor's OkHttp engine treats channel content as one-shot, including the empty request POST. */
internal fun verificationPostBody(proof: EmailVerificationProof?): OutgoingContent {
    val bytes = proof?.let { ContractJson.encodeToString(it).encodeToByteArray() } ?: byteArrayOf()
    return object : OutgoingContent.ReadChannelContent() {
        override val contentType = ContentType.Application.Json.takeIf { proof != null }
        override val contentLength = bytes.size.toLong()
        override fun readFrom() = ByteReadChannel(bytes)
    }
}

@Serializable
private class EmailActionChallengeDto(val challengeId: String, val expiresAt: String, val resendAfterSeconds: Int)

private suspend fun HttpResponse.readChallenge(): EmailActionChallenge = try {
    val dto = ContractJson.decodeFromString<EmailActionChallengeDto>(bodyAsText())
    require(CHALLENGE_ID.matches(dto.challengeId))
    require(dto.resendAfterSeconds == EMAIL_RESEND_SECONDS)
    EmailActionChallenge(dto.challengeId, Instant.parse(dto.expiresAt), dto.resendAfterSeconds)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    throw AuthException(AuthError.InvalidResponse)
}

private suspend fun HttpResponse.requireEmptyVerificationBody() {
    if (bodyAsText().isNotEmpty()) throw AuthException(AuthError.InvalidResponse)
}
