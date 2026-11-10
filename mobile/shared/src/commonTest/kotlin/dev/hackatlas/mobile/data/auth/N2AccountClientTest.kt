package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class N2AccountClientTest {
    @Test
    fun verificationUsesCapturedBearerAndPreservesLeadingZeroAndSession() = runTest {
        val requests = mutableListOf<String>()
        val harness = VerificationHarness { request ->
            val path = request.url.encodedPath.removePrefix("/api/v1")
            requests += path
            assertEquals("Bearer ${VerificationHarness.bearer}", request.headers[HttpHeaders.Authorization])
            when (path) {
                "/me" -> respondWith(if (requests.count { it == "/me" } == 1) VerificationHarness.pending else VerificationHarness.verified)
                VerificationHarness.requestPath -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertTrue(request.body.toByteArray().isEmpty())
                    respondWith(VerificationHarness.accepted)
                }
                VerificationHarness.confirmPath -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val payload = ContractJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    assertEquals(setOf("challengeId", "code"), payload.keys)
                    assertEquals(VerificationHarness.challengeId, payload.getValue("challengeId").jsonPrimitive.content)
                    assertEquals("01234567", payload.getValue("code").jsonPrimitive.content)
                    assertTrue(payload.getValue("code").jsonPrimitive.isString)
                    respondWith(N2HttpFixtures.response(path, 204))
                }
                else -> error("Unexpected verification path")
            }
        }
        try {
            harness.session.restore()
            val owner = assertNotNull(harness.session.localIdentity.value)
            val challenge = assertIs<VerificationRequestResult.Accepted>(harness.client.requestVerification(owner)).challenge
            assertEquals(60, challenge.resendAfterSeconds)
            assertEquals(VerificationHarness.challengeExpiry, challenge.expiresAt)
            assertFalse(challenge.toString().contains(VerificationHarness.challengeId))
            assertFailsWith<IllegalArgumentException> {
                harness.client.confirmVerification(owner, challenge, "٠١٢٣٤٥٦٧")
            }
            harness.client.confirmVerification(owner, challenge, "01234567")
            assertFalse(harness.session.currentPrivateAccess()!!.canRead)
            assertTrue(harness.client.checkVerification(owner))
            assertTrue(harness.session.currentPrivateAccess()!!.canRead)
            assertEquals(owner, harness.session.localIdentity.value)
            assertEquals(VerificationHarness.bearer, harness.disk.session?.accessToken)
            assertEquals(harness.expiry, harness.disk.session?.expiresAt)
            assertEquals(listOf("/me", VerificationHarness.requestPath, VerificationHarness.confirmPath, "/me"), requests)
        } finally {
            harness.session.close()
        }
    }

    @Test
    fun requestDistinguishesAlreadyVerifiedAndQuotaAndRejectsAnotherGeneration() = runTest {
        var sent = 0
        val harness = VerificationHarness { request ->
            if (request.url.encodedPath.endsWith("/me")) respondWith(VerificationHarness.pending)
            else respondWith(N2HttpFixtures.response(VerificationHarness.requestPath, if (++sent == 1) 204 else 429))
        }
        try {
            harness.session.restore()
            val owner = assertNotNull(harness.session.localIdentity.value)
            assertFailsWith<CancellationException> {
                harness.client.requestVerification(owner.copy(generation = "stale"))
            }
            assertEquals(VerificationRequestResult.AlreadyVerified, harness.client.requestVerification(owner))
            val limited = assertFailsWith<AuthException> { harness.client.requestVerification(owner) }
            val http = assertIs<AuthError.Http>(limited.error)
            assertEquals(N2HttpAction.RATE_LIMITED, http.n2Action)
            assertEquals(N2HttpFixtures.response(VerificationHarness.requestPath, 429).retryAfter.toInt(), http.retryAfterSeconds)
            assertEquals(2, sent)
            assertFalse(harness.session.currentPrivateAccess()!!.canRead)
        } finally {
            harness.session.close()
        }
    }

    @Test
    fun proofThatExpiresWhileReadingSessionIsNeverSent() = runTest {
        var now = VerificationHarness.challengeExpiry - 1.seconds
        val harness = VerificationHarness(clock = { now }) { request ->
            when (request.url.encodedPath.removePrefix("/api/v1")) {
                "/me" -> respondWith(VerificationHarness.pending)
                VerificationHarness.requestPath -> respondWith(VerificationHarness.accepted)
                else -> error("Expired proof reached the network")
            }
        }
        try {
            harness.session.restore()
            val owner = assertNotNull(harness.session.localIdentity.value)
            val challenge = assertIs<VerificationRequestResult.Accepted>(harness.client.requestVerification(owner)).challenge
            harness.disk.onRead = { now = VerificationHarness.challengeExpiry }
            assertFailsWith<VerificationChallengeExpired> {
                harness.client.confirmVerification(owner, challenge, "01234567")
            }
            assertEquals(owner, harness.session.localIdentity.value)
        } finally {
            harness.session.close()
        }
    }
}

internal class VerificationHarness(
    val clock: () -> Instant = { challengeExpiry - 600.seconds },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val source = CatalogSource.Remote("https://n2.example.org/api/v1/")
    val expiry = (challengeExpiry + 3600.seconds).toString()
    val disk = MemoryStore(source.baseUrl, expiry)
    val session = AuthSessionClient(MockEngine.create {
        this.dispatcher = dispatcher
        addHandler(respond)
    }, source, disk, AuthProtocol.CLOSED_N2, now = clock)
    val client = N2AccountClient(session)

    class MemoryStore(override val apiBaseUrl: String, expiry: String) : SecureTokenStore {
        var onRead: () -> Unit = {}
        var session: StoredSession? = StoredSession(
            bearer, expiry, pending.body!!.getValue("id").jsonPrimitive.content, "11111111-1111-4111-8111-111111111111",
        )
        override suspend fun read(): StoredSession? {
            onRead()
            return session
        }
        override suspend fun write(session: StoredSession) { this.session = session }
        override suspend fun clear() { session = null }
    }
    companion object {
        const val requestPath = "/auth/email-verification/request"
        const val confirmPath = "/auth/email-verification/confirm"
        val accepted = N2HttpFixtures.response(requestPath, 202)
        val challengeId = accepted.body!!.getValue("challengeId").jsonPrimitive.content
        val challengeExpiry = Instant.parse(accepted.body!!.getValue("expiresAt").jsonPrimitive.content)
        val pending = N2HttpFixtures.response("/me", 200) { it.body!!.getValue("emailVerified").jsonPrimitive.content == "false" }
        val verified = N2HttpFixtures.response("/me", 200) {
            it.body!!.getValue("emailVerified").jsonPrimitive.content == "true"
        }.let { it.copy(body = JsonObject(it.body!! + ("id" to pending.body!!.getValue("id")))) }
        val bearer = "mock-verification-opaque-" + "a".repeat(20)
    }
}
