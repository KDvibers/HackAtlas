package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.CanonicalFixtures
import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.data.ProfileDto
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthSessionClientTest {
    private val source = CatalogSource.Remote("https://api.example.org/api/v1")
    private val now = Instant.parse("2026-10-31T10:00:00Z")
    private val expiry = "2026-11-30T10:00:00Z"
    private val token = "synthetic-opaque-session-" + "a".repeat(32)
    private val profile = CanonicalFixtures.files.getValue("profile.json")
    private val user = ContractJson.decodeFromString<ProfileDto>(profile)
    private fun session() = StoredSession(token, expiry, user.id)
    private fun response(tokenType: String = "Bearer", accessToken: String = token, expires: String = expiry) = buildJsonObject {
        put("tokenType", tokenType); put("accessToken", accessToken); put("expiresAt", expires)
        put("user", ContractJson.parseToJsonElement(profile))
    }.toString()

    private class MemoryStore(override val apiBaseUrl: String) : SecureTokenStore {
        var value: StoredSession? = null
        var failRead = false; var failWrite = false; var failClear = false
        var beforeWrite: (suspend () -> Unit)? = null
        override suspend fun read(): StoredSession? { if (failRead) throw SecureStorageException(); return value }
        override suspend fun write(session: StoredSession) { beforeWrite?.invoke(); if (failWrite) throw SecureStorageException(); value = session }
        override suspend fun clear() { if (failClear) throw SecureStorageException(); value = null }
    }

    private suspend fun checkClient(
        store: MemoryStore = MemoryStore(source.baseUrl),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        block: suspend (AuthSessionClient, MemoryStore) -> Unit,
    ) {
        val client = AuthSessionClient(MockEngine(handler), source, store) { now }
        try { block(client, store) } finally { client.close() }
    }

    @Test fun registerUsesContractWithoutBearerAndPreservesPassword() = runTest {
        checkClient(handler = { request ->
            assertEquals(source.baseUrl + "auth/register", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals(ContentType.Application.Json, request.body.contentType)
            val json = ContractJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertEquals(setOf("email", "password", "displayName"), json.keys)
            assertEquals(" password with spaces ", json.getValue("password").jsonPrimitive.content)
            respond(response(), HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { client, store ->
            assertEquals(user, client.register("name@example.org", " password with spaces ", "Name"))
            assertEquals(token, store.value?.accessToken)
            assertIs<AuthState.Authenticated>(client.state.value)
            assertFalse(client.state.value.toString().contains(token))
            assertFalse(store.value.toString().contains(token))
        }
    }

    @Test fun loginNeverSendsExistingBearer() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { request ->
            assertEquals("/api/v1/auth/login", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(response(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { client, _ -> assertEquals(user, client.login("name@example.org", " untouched ")) }
    }

    @Test fun restoredSessionIsCheckedWithMeAndSingleBearer() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { request ->
            assertEquals(source.baseUrl + "me", request.url.toString())
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(listOf("Bearer $token"), request.headers.getAll(HttpHeaders.Authorization))
            assertTrue(request.url.parameters.isEmpty())
            respond(profile, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { client, _ -> assertEquals(user, client.restore()); assertIs<AuthState.Authenticated>(client.state.value) }
    }

    @Test fun missingSessionDoesNotCallNetwork() = runTest {
        checkClient(handler = { error("No request expected") }) { client, _ ->
            assertNull(client.restore()); assertEquals(AuthState.SignedOut, client.state.value)
        }
    }

    @Test fun expiredSessionRequiresLoginWithoutNetworkOrRefresh() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = StoredSession(token, now.toString(), user.id) }
        checkClient(store, { error("Expired session must not be sent") }) { client, memory ->
            assertEquals(AuthError.Unauthorized, assertFailsWith<AuthException> { client.restore() }.error)
            assertNull(memory.value); assertEquals(AuthState.SignInRequired, client.state.value)
        }
    }

    @Test fun revokedMeSessionIsClearedWithoutRetry() = runTest {
        var calls = 0
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { calls++; respond("sensitive body", HttpStatusCode.Unauthorized) }) { client, memory ->
            assertEquals(AuthError.Unauthorized, assertFailsWith<AuthException> { client.profile() }.error)
            assertEquals(1, calls); assertNull(memory.value); assertEquals(AuthState.SignInRequired, client.state.value)
        }
    }

    @Test fun failedLoginDoesNotEraseExistingSession() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { respond("no details", HttpStatusCode.Unauthorized) }) { client, memory ->
            assertIs<AuthError.Http>(assertFailsWith<AuthException> { client.login("a@b.org", "wrong") }.error)
            assertEquals(token, memory.value?.accessToken)
        }
    }

    @Test fun logoutConfirmsRevocationOnlyAfter204() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { request ->
            assertEquals(source.baseUrl + "auth/logout", request.url.toString())
            assertEquals("Bearer $token", request.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.NoContent)
        }) { client, memory ->
            assertEquals(LogoutResult.Revoked, client.logout()); assertNull(memory.value)
            assertEquals(AuthState.SignedOut, client.state.value)
        }
    }

    @Test fun offlineLogoutClearsLocallyWithoutClaimingRevocation() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { throw IOException("secret=$token") }) { client, memory ->
            assertEquals(AuthError.Network, assertIs<LogoutResult.LocalOnly>(client.logout()).error)
            assertNull(memory.value); assertEquals(AuthState.SignedOut, client.state.value)
        }
    }

    @Test fun cancelledLogoutStillClearsButPropagatesCancellation() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { throw CancellationException("cancel") }) { client, memory ->
            assertFailsWith<CancellationException> { client.logout() }
            assertNull(memory.value); assertEquals(AuthState.SignedOut, client.state.value)
        }
    }

    @Test fun serverFailureLogoutIsLocalOnly() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { respond("private", HttpStatusCode.ServiceUnavailable) }) { client, memory ->
            assertEquals(503, assertIs<AuthError.Http>(assertIs<LogoutResult.LocalOnly>(client.logout()).error).status)
            assertNull(memory.value)
        }
    }

    @Test fun malformedAuthResponseNeverReplacesSession() = runTest {
        for (body in listOf("{}", response("JWT"), response(accessToken = "bad\r\nHeader"),
            response(expires = "invalid"), response(expires = now.toString()))) {
            checkClient(handler = { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { client, store ->
                assertEquals(AuthError.InvalidResponse, assertFailsWith<AuthException> { client.login("a@b.org", "password") }.error)
                assertNull(store.value); assertFalse(client.state.value is AuthState.Authenticated)
            }
        }
    }

    @Test fun redirectIsNotFollowedAndCredentialsNotSentElsewhere() = runTest {
        var count = 0
        checkClient(handler = { request ->
            count++; assertEquals("api.example.org", request.url.host)
            respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://evil.example.org/me"))
        }) { client, _ ->
            assertEquals(307, assertIs<AuthError.Http>(assertFailsWith<AuthException> { client.login("a@b.org", "private-password") }.error).status)
            assertEquals(1, count)
        }
    }

    @Test fun httpErrorsExposeOnlyStatusAndRetryAfter() = runTest {
        checkClient(handler = { respond("password=$token", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "12")) }) { client, _ ->
            val failure = assertFailsWith<AuthException> { client.login("a@b.org", "password") }
            val error = assertIs<AuthError.Http>(failure.error)
            assertEquals(429, error.status); assertEquals(12, error.retryAfterSeconds)
            assertFalse(failure.toString().contains(token)); assertNull(failure.cause)
        }
    }

    @Test fun storageFailureNeverPublishesAuthenticated() = runTest {
        val store = MemoryStore(source.baseUrl).apply { failWrite = true }
        checkClient(store, { respond(response(), headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { client, _ ->
            assertEquals(AuthError.Storage, assertFailsWith<AuthException> { client.login("a@b.org", "password") }.error)
            assertEquals(AuthState.StorageUnavailable, client.state.value)
        }
    }

    @Test fun unreadableStoreIsNotAnEmptySession() = runTest {
        val store = MemoryStore(source.baseUrl).apply { failRead = true }
        checkClient(store, { error("No network") }) { client, _ ->
            assertEquals(AuthError.Storage, assertFailsWith<AuthException> { client.restore() }.error)
            assertEquals(AuthState.StorageUnavailable, client.state.value)
        }
    }

    @Test fun failedClearDoesNotClaimLogoutSuccess() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session(); failClear = true }
        checkClient(store, { respond("", HttpStatusCode.NoContent) }) { client, _ ->
            assertEquals(AuthError.Storage, assertFailsWith<AuthException> { client.logout() }.error)
            assertEquals(AuthState.StorageUnavailable, client.state.value)
        }
    }

    @Test fun mismatchedApiStoreIsRejectedBeforeNetwork() {
        val engine = MockEngine { error("No network") }
        try {
            assertFailsWith<IllegalArgumentException> {
                AuthSessionClient(engine, source, MemoryStore("https://other.example.org/api/v1/")) { now }
            }
        } finally { engine.close() }
    }

    @Test fun cancellationOfLoginDoesNotErasePreviousSession() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { throw CancellationException("cancel") }) { client, memory ->
            assertFailsWith<CancellationException> { client.login("a@b.org", "password") }
            assertEquals(token, memory.value?.accessToken)
        }
    }

    @Test fun concurrentLogoutThenLoginCannotClearNewSession() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { request ->
            if (request.url.encodedPath.endsWith("logout")) {
                entered.complete(Unit); release.await(); respond("", HttpStatusCode.NoContent)
            } else respond(response(accessToken = token + "new"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { client, memory ->
            val logout = async { client.logout() }; entered.await()
            val login = async { client.login("a@b.org", "password") }
            release.complete(Unit); logout.await(); login.await()
            assertEquals(token + "new", memory.value?.accessToken)
            assertIs<AuthState.Authenticated>(client.state.value)
        }
    }

    @Test fun cancelledCallerDuringWriteCannotSplitPersistedSessionAndState() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = MemoryStore(source.baseUrl).apply { beforeWrite = { entered.complete(Unit); release.await() } }
        checkClient(store, { respond(response(), headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { client, memory ->
            val login = launch { client.login("a@b.org", "password") }
            entered.await(); login.cancel(); release.complete(Unit); login.join()
            assertEquals(token, memory.value?.accessToken)
            assertIs<AuthState.Authenticated>(client.state.value)
        }
    }

    @Test fun timeoutIsTypedWithoutLeakingCredentialsOrRetrying() = runTest {
        var requests = 0
        checkClient(handler = { request -> requests++; throw HttpRequestTimeoutException(request) }) { client, store ->
            val failure = assertFailsWith<AuthException> { client.login("a@b.org", "private-password") }
            assertEquals(AuthError.Timeout, failure.error); assertNull(failure.cause)
            assertEquals(1, requests); assertNull(store.value)
        }
    }

    @Test fun restoredIdentityMismatchNeverAuthenticatesAnotherAccount() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = StoredSession(token, expiry, "00000000-0000-0000-0000-000000000099") }
        checkClient(store, { respond(profile, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) { client, _ ->
            assertEquals(AuthError.InvalidResponse, assertFailsWith<AuthException> { client.restore() }.error)
            assertFalse(client.state.value is AuthState.Authenticated)
        }
    }

    @Test fun malformedStoredTokenIsStorageErrorAndNeverSent() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = StoredSession("invalid\r\n", expiry, user.id) }
        checkClient(store, { error("Invalid stored token must not be sent") }) { client, _ ->
            assertEquals(AuthError.Storage, assertFailsWith<AuthException> { client.restore() }.error)
            assertEquals(AuthState.StorageUnavailable, client.state.value)
        }
    }

    @Test fun alreadyRevokedLogoutStillClearsLocalSession() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session() }
        checkClient(store, { respond("invalid", HttpStatusCode.Unauthorized) }) { client, memory ->
            assertEquals(LogoutResult.AlreadyInvalid, client.logout()); assertNull(memory.value)
        }
    }

    @Test fun logoutWithoutStoredTokenDoesNotClaimServerInvalidation() = runTest {
        checkClient(handler = { error("No network without a token") }) { client, _ ->
            val result = client.logout()
            assertFalse(result is LogoutResult.AlreadyInvalid, "No token does not prove server revocation")
            assertFalse(result is LogoutResult.Revoked)
            assertEquals(AuthState.SignedOut, client.state.value)
        }
    }

    @Test fun logoutClearsUnreadableCredentialsWithoutClaimingRevocation() = runTest {
        val store = MemoryStore(source.baseUrl).apply { value = session(); failRead = true }
        checkClient(store, { error("Unreadable token must not be sent") }) { client, memory ->
            assertEquals(AuthError.Storage, assertIs<LogoutResult.LocalOnly>(client.logout()).error)
            assertNull(memory.value); assertEquals(AuthState.SignedOut, client.state.value)
        }
    }
}
