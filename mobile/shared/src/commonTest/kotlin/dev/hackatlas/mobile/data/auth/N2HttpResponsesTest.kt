package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.encodedPath
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class N2HttpResponsesTest {
    private val source = CatalogSource.Remote("https://n2.example.org/test/api/v1/")
    private val pendingProfile = N2HttpFixtures.response("/me", 200) { !verified(it) }
    private val verifiedProfile = N2HttpFixtures.response("/me", 200, ::verified)
    private val pendingRegister = N2HttpFixtures.response("/auth/register", 201)
    private val pendingLogin = N2HttpFixtures.response("/auth/login", 200) { !verified(it) }
    private val verifiedLogin = N2HttpFixtures.response("/auth/login", 200, ::verified)
    private val now = Instant.parse(pendingRegister.body!!.getValue("expiresAt").jsonPrimitive.content) - 60.seconds
    private val expiry = (now + 3_600.seconds).toString()
    private val testBearer = "mock-opaque-n2-session-" + "a".repeat(20)

    @Test
    fun n2RegisterAndLoginReadTheVerificationFlagBeforeSavingASession() = runTest {
        for (capture in listOf(pendingRegister, pendingLogin, verifiedLogin)) {
            val body = assertNotNull(capture.body)
            val clock = Instant.parse(body.getValue("expiresAt").jsonPrimitive.content) - 60.seconds
            val store = MemoryStore()
            val engine = MockEngine { request ->
                assertEquals(capture.requestMethod, request.method)
                assertEquals("/test/api/v1${capture.requestPath}", request.url.encodedPath)
                assertNull(request.headers[HttpHeaders.Authorization])
                respondWith(capture)
            }
            val client = AuthSessionClient(engine, source, store, AuthProtocol.CLOSED_N2) { clock }
            try {
                if (capture.status == 201) client.register("fixture@example.org", "sample-password", "Fixture")
                else client.login("fixture@example.org", "sample-password")
                val expected = if (verified(capture)) EmailVerification.VERIFIED else EmailVerification.PENDING
                assertEquals(expected, assertIs<AuthState.Authenticated>(client.state.value).emailVerification)
                assertEquals(body.getValue("expiresAt").jsonPrimitive.content, store.session?.expiresAt)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun n2MissingAndMalformedFlagsAreDifferentAndCannotReplaceAStoredSession() = runTest {
        val original = assertNotNull(verifiedLogin.body)
        val profile = original.getValue("user").jsonObject
        val cases = listOf(
            null to AuthError.UnsupportedServer,
            JsonNull to AuthError.InvalidResponse,
            JsonPrimitive("true") to AuthError.InvalidResponse,
            JsonPrimitive(1) to AuthError.InvalidResponse,
        )
        for ((flag, expected) in cases) {
            val replacement = if (flag == null) JsonObject(profile - "emailVerified")
                else JsonObject(profile + ("emailVerified" to flag))
            val body = JsonObject(original + ("user" to replacement))
            val stored = sessionFor(verifiedProfile)
            val store = MemoryStore(stored)
            val client = AuthSessionClient(MockEngine { respondWith(verifiedLogin, body) }, source, store, AuthProtocol.CLOSED_N2) { now }
            try {
                val failure = assertFailsWith<AuthException> { client.login("fixture@example.org", "sample-password") }
                assertEquals(expected, failure.error)
                assertSame(stored, store.session)
                assertEquals(AuthState.NotLoaded, client.state.value)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun legacyCanStillReadProfilesWithoutAFlag() = runTest {
        val capture = verifiedProfile
        val body = JsonObject(assertNotNull(capture.body) - "emailVerified")
        val store = MemoryStore(sessionFor(capture))
        val client = AuthSessionClient(MockEngine { respondWith(capture, body) }, source, store) { now }
        try {
            assertEquals(store.session?.userId, client.profile().id)
            assertNull(assertIs<AuthState.Authenticated>(client.state.value).emailVerification)
        } finally {
            client.close()
        }
    }

    @Test
    fun n2ProfileChecksTheFlagOnRestoreAndPatch() = runTest {
        val valid = pendingProfile
        val malformed = JsonObject(assertNotNull(valid.body) + ("emailVerified" to JsonPrimitive("false")))
        val stored = sessionFor(valid)
        val store = MemoryStore(stored)
        val engine = MockEngine { request ->
            assertEquals("Bearer $testBearer", request.headers[HttpHeaders.Authorization])
            if (request.method.value == "GET") respondWith(valid) else respondWith(valid, malformed)
        }
        val client = AuthSessionClient(engine, source, store, AuthProtocol.CLOSED_N2) { now }
        try {
            client.restore()
            assertEquals(EmailVerification.PENDING, assertIs<AuthState.Authenticated>(client.state.value).emailVerification)
            val failure = assertFailsWith<AuthException> {
                client.updateProfile(stored.userId, JsonObject(mapOf("displayName" to JsonPrimitive("Updated"))))
            }
            assertEquals(AuthError.InvalidResponse, failure.error)
            assertSame(stored, store.session)
        } finally {
            client.close()
        }
    }

    @Test
    fun failedLoginAndResetRequiredPreserveTheCurrentSession() = runTest {
        val cases = mapOf(401 to N2HttpAction.INVALID_CREDENTIALS, 403 to N2HttpAction.PASSWORD_RESET_REQUIRED)
        for ((status, action) in cases) {
            val profile = verifiedProfile
            val denial = N2HttpFixtures.response("/auth/login", status)
            val stored = sessionFor(profile)
            val store = MemoryStore(stored)
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/auth/login")) {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respondWith(denial)
                } else respondWith(profile)
            }
            val client = AuthSessionClient(engine, source, store, AuthProtocol.CLOSED_N2) { now }
            try {
                client.profile()
                val state = client.state.value
                val failure = assertFailsWith<AuthException> { client.login("fixture@example.org", "sample-password") }
                assertEquals(action, assertIs<AuthError.Http>(failure.error).n2Action)
                assertSame(stored, store.session)
                assertSame(state, client.state.value)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun deletePasswordPairsPreserveBearerForTheFollowingProfileRequest() = runTest {
        for (sequence in N2HttpFixtures.deletePassword.sequences) {
            val (denial, profile) = N2HttpFixtures.deletePasswordPair(sequence)
            val stored = sessionFor(profile)
            val store = MemoryStore(stored)
            var calls = 0
            val engine = MockEngine { request ->
                val capture = listOf(denial, profile)[calls++]
                assertEquals(capture.requestMethod, request.method)
                assertEquals("/test/api/v1${capture.requestPath}", request.url.encodedPath)
                assertEquals(listOf("Bearer $testBearer"), request.headers.getAll(HttpHeaders.Authorization))
                respondWith(capture)
            }
            val mockTransport = HttpClient(engine) { expectSuccess = false }
            val client = AuthSessionClient(engine, source, store, AuthProtocol.CLOSED_N2) { now }
            try {
                val response = mockTransport.request(source.baseUrl + "me/delete-account") {
                    method = denial.requestMethod
                    bearerAuth(testBearer)
                }
                val failure = assertFailsWith<AuthException> {
                    response.requireN2Success(source, 204, authenticated = true) { store.clear() }
                }
                assertEquals(N2HttpAction.INVALID_CREDENTIALS, assertIs<AuthError.Http>(failure.error).n2Action)
                assertSame(stored, store.session)
                assertEquals(stored.userId, client.profile().id)
                val expected = if (verified(profile)) EmailVerification.VERIFIED else EmailVerification.PENDING
                assertEquals(expected, assertIs<AuthState.Authenticated>(client.state.value).emailVerification)
                assertEquals(2, calls)
            } finally {
                mockTransport.close()
                client.close()
            }
        }
    }

    @Test
    fun pending403ForASavedRequestKeepsSessionAndReportsPending() = runTest {
        val denial = N2HttpFixtures.response("/me/saved", 403)
        val stored = sessionFor(verifiedProfile)
        val store = MemoryStore(stored)
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/me/saved")) respondWith(denial) else respondWith(verifiedProfile)
        }
        val client = AuthSessionClient(engine, source, store, AuthProtocol.CLOSED_N2) { now }
        try {
            client.profile()
            val failure = assertFailsWith<AuthException> { client.listSaved(stored.userId) }
            assertEquals(N2HttpAction.EMAIL_NOT_VERIFIED, assertIs<AuthError.Http>(failure.error).n2Action)
            assertEquals(EmailVerification.PENDING, assertIs<AuthState.Authenticated>(client.state.value).emailVerification)
            assertSame(stored, store.session)
        } finally {
            client.close()
        }
    }

    @Test
    fun protected401ClearsCurrentSessionEvenWhenProblemCannotBeTrusted() = runTest {
        val unauthorized = N2HttpFixtures.response("/me", 401)
        val deletePassword = N2HttpFixtures.deletePassword.responses.first()
        val cases = listOf(
            unauthorized,
            unauthorized.copy(headers = mapOf("Content-Type" to "text/html")),
            unauthorized.copy(body = deletePassword.body),
        )
        for (capture in cases) {
            val store = MemoryStore(sessionFor(verifiedProfile))
            val client = AuthSessionClient(MockEngine { respondWith(capture) }, source, store, AuthProtocol.CLOSED_N2) { now }
            try {
                assertEquals(AuthError.Unauthorized, assertFailsWith<AuthException> { client.profile() }.error)
                assertNull(store.session)
                assertEquals(AuthState.SignInRequired, client.state.value)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun onlyTheNineGuardedOperationsAcceptEmailNotVerified() = runTest {
        val guards = N2HttpFixtures.full.responses.filter { it.code == "EMAIL_NOT_VERIFIED" }
        assertEquals(9, guards.distinctBy { it.method to it.operationPath }.size)
        for (capture in guards) {
            val error = assertIs<AuthError.Http>(applyFailure(capture, authenticated = true))
            assertEquals(N2HttpAction.EMAIL_NOT_VERIFIED, error.n2Action)
            assertEquals(capture.body?.get("requestId")?.jsonPrimitive?.content, error.requestId)
        }
        assertEquals(AuthError.InvalidResponse, applyFailure(guards.first().copy(method = "get", operationPath = "/me"), true))
        assertEquals(AuthError.InvalidResponse, applyFailure(guards.first().copy(method = "post"), true))
        assertEquals(AuthError.InvalidResponse, applyFailure(guards.first(), authenticated = false))
    }

    @Test
    fun wrongProblemStatusAndWrongRecoveryPathCannotChangeAccess() = runTest {
        val recovery = N2HttpFixtures.response("/auth/login", 403)
        val body = assertNotNull(recovery.body)
        assertEquals(AuthError.InvalidResponse, applyFailure(recovery.copy(operationPath = "/suggestions"), true))
        assertEquals(AuthError.InvalidResponse, applyFailure(recovery.copy(body = JsonObject(body + ("status" to JsonPrimitive(401))))))
        val forbidden = recovery.copy(
            operationPath = "/auth/register",
            body = JsonObject(body + ("code" to JsonPrimitive("FORBIDDEN"))),
        )
        assertEquals(N2HttpAction.FORBIDDEN, assertIs<AuthError.Http>(applyFailure(forbidden)).n2Action)
        val unknown = forbidden.copy(body = JsonObject(body + ("code" to JsonPrimitive("UNSUPPORTED_CODE"))))
        assertEquals(AuthError.InvalidResponse, applyFailure(unknown))
    }

    @Test
    fun stringRequestIdIsAcceptedAndCannotDisagreeWithTheHeader() = runTest {
        val capture = N2HttpFixtures.response("/auth/login", 403)
        val requestId = "n2-request-7"
        val body = JsonObject(assertNotNull(capture.body) + ("requestId" to JsonPrimitive(requestId)))
        val matching = capture.copy(
            body = body,
            headers = capture.headers.filterKeys { !it.equals("X-Request-Id", ignoreCase = true) } + ("X-Request-Id" to requestId),
        )
        val error = assertIs<AuthError.Http>(applyFailure(matching))
        assertEquals(N2HttpAction.PASSWORD_RESET_REQUIRED, error.n2Action)
        assertEquals(requestId, error.requestId)
        assertFalse(error.toString().contains(requestId))
        assertEquals(AuthError.InvalidResponse, applyFailure(capture.copy(body = body)))
    }

    @Test
    fun recoveryErrorsReportRetryOrIncompatibleServerWithoutInvalidatingOtherSessions() = runTest {
        val rateLimit = N2HttpFixtures.response("/auth/email-verification/request", 429)
        val limited = assertIs<AuthError.Http>(applyFailure(rateLimit, true))
        assertEquals(N2HttpAction.RATE_LIMITED, limited.n2Action)
        assertEquals(rateLimit.retryAfter.toInt(), limited.retryAfterSeconds)

        val unavailable = N2HttpFixtures.response("/auth/password-reset/request", 503)
        assertEquals(N2HttpAction.RETRY_MANUALLY, assertIs<AuthError.Http>(applyFailure(unavailable)).n2Action)
        val invalid = N2HttpFixtures.response("/auth/password-reset/verify", 400)
        assertEquals(N2HttpAction.INVALID_EMAIL_ACTION, assertIs<AuthError.Http>(applyFailure(invalid)).n2Action)
        assertEquals(AuthError.UnsupportedServer, applyFailure(unavailable.copy(status = 404)))
        assertEquals(AuthError.UnsupportedServer, applyFailure(unavailable.copy(status = 501)))
        assertEquals(AuthError.UnsupportedServer, applyFailure(unavailable.copy(status = 401)))
        assertEquals(AuthError.UnsupportedServer, applyFailure(unavailable.copy(status = 401), authenticated = true))
    }

    private suspend fun applyFailure(capture: N2CapturedResponse, authenticated: Boolean = false): AuthError {
        val engine = MockEngine { respondWith(capture) }
        val client = HttpClient(engine) { expectSuccess = false }
        try {
            val response = client.request(source.baseUrl + capture.requestPath.removePrefix("/")) {
                method = capture.requestMethod
                if (authenticated) bearerAuth(testBearer)
            }
            var invalidated = false
            val error = assertFailsWith<AuthException> {
                response.requireN2Success(source, 200, authenticated) { invalidated = true }
            }.error
            assertFalse(invalidated)
            return error
        } finally {
            client.close()
            engine.close()
        }
    }

    private fun verified(response: N2CapturedResponse): Boolean {
        val body = assertNotNull(response.body)
        return (body["user"]?.jsonObject ?: body).getValue("emailVerified").jsonPrimitive.boolean
    }

    private fun sessionFor(profile: N2CapturedResponse) = StoredSession(
        testBearer, expiry, assertNotNull(profile.body).getValue("id").jsonPrimitive.content,
    )

    private inner class MemoryStore(var session: StoredSession? = null) : SecureTokenStore {
        override val apiBaseUrl = source.baseUrl
        override suspend fun read() = session
        override suspend fun write(session: StoredSession) { this.session = session }
        override suspend fun clear() { session = null }
    }
}
