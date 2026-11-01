package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthStoreTest {
    private val source = CatalogSource.Remote("https://auth.example.org/api/v1/")
    private val profileJson = CanonicalFixtures.files.getValue("profile.json")
    private val user = ContractJson.decodeFromString<ProfileDto>(profileJson)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val token = "synthetic-ui-session-" + "x".repeat(40)
    private fun session() = StoredSession(token, "2026-12-01T00:00:00Z", user.id)
    private fun response(profile: String = profileJson) = buildJsonObject {
        put("tokenType", "Bearer"); put("accessToken", token); put("expiresAt", session().expiresAt)
        put("user", ContractJson.parseToJsonElement(profile))
    }.toString()
    private class MemoryStore(override val apiBaseUrl: String) : SecureTokenStore {
        var value: StoredSession? = null
        var failRead = false; var failClear = false
        override suspend fun read(): StoredSession? { if (failRead) throw SecureStorageException(); return value }
        override suspend fun write(session: StoredSession) { value = session }
        override suspend fun clear() { if (failClear) throw SecureStorageException(); value = null }
    }
    private fun TestScope.client(disk: MemoryStore, handler: MockEngineConfig.() -> Unit) =
        AuthSessionClient(MockEngine.create {
            dispatcher = StandardTestDispatcher(testScheduler)
            handler()
        }, source, disk) { Instant.parse("2026-11-01T12:00:00Z") }

    @Test fun validationMatchesStarterAndNeverTrimsPassword() {
        assertTrue(validateAuthForm(AuthFormMode.LOGIN, "a@example.org", " ", "").isEmpty())
        assertTrue(validateAuthForm(AuthFormMode.REGISTER, "a@example.org", " ".repeat(12), "Name").isEmpty())
        assertEquals(AuthFieldError.PASSWORD_SHORT, validateAuthForm(AuthFormMode.REGISTER, "a@b.org", "short", "N").password)
        assertEquals(AuthFieldError.PASSWORD_LONG, validateAuthForm(AuthFormMode.LOGIN, "a@b.org", "x".repeat(257), "").password)
        assertNotNull(validateAuthForm(AuthFormMode.LOGIN, "invalid", "x", "").email)
        assertNotNull(validateAuthForm(AuthFormMode.REGISTER, "a@b.org", "x".repeat(12), " ").name)
        assertNotNull(validateAuthForm(AuthFormMode.REGISTER, "a@b.org", "x".repeat(12), "x".repeat(61)).name)
    }

    @Test fun startupWithoutCredentialDoesNotUseNetworkAndIsIdempotent() = runTest {
        val disk = MemoryStore(source.baseUrl)
        val client = client(disk) { addHandler { error("Unexpected HTTP") } }
        try {
            val store = AuthStore(client, backgroundScope)
            store.start(); store.start(); runCurrent()
            assertEquals(AuthState.SignedOut, store.state.value.session)
            assertNull(store.state.value.busy)
            assertTrue(store.state.value.canAuthenticate)
        } finally { client.close() }
    }

    @Test fun submitValidationDoesNotSendRequest() = runTest {
        val client = client(MemoryStore(source.baseUrl)) { addHandler { error("Unexpected HTTP") } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            assertFalse(store.submit(AuthFormMode.REGISTER, "a@b.org", "short", "N"))
            assertEquals(AuthFieldError.PASSWORD_SHORT, store.state.value.fields.password)
        } finally { client.close() }
    }

    @Test fun duplicateSubmitCannotQueueAnotherLoginAndPasswordIsUnchanged() = runTest {
        val release = CompletableDeferred<Unit>(); var calls = 0
        val client = client(MemoryStore(source.baseUrl)) { addHandler {
            calls++
            val json = ContractJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            assertEquals("a@example.org", json["email"]!!.jsonPrimitive.content)
            assertEquals(" untouched ", json["password"]!!.jsonPrimitive.content)
            assertNull(it.headers[HttpHeaders.Authorization])
            release.await(); respond(response(), headers = jsonHeaders)
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            assertTrue(store.submit(AuthFormMode.LOGIN, " a@example.org ", " untouched ", ""))
            assertFalse(store.submit(AuthFormMode.LOGIN, "a@example.org", "second", ""))
            runCurrent(); assertEquals(1, calls); assertEquals(AuthOperation.LOGIN, store.state.value.busy)
            release.complete(Unit); runCurrent()
            assertEquals(user, assertIs<AuthState.Authenticated>(store.state.value.session).user)
            assertFalse(store.state.value.toString().contains(user.email))
        } finally { client.close() }
    }

    @Test fun registerUsesTrimmedNameAndShowsActualProfile() = runTest {
        val client = client(MemoryStore(source.baseUrl)) { addHandler {
            assertEquals("/api/v1/auth/register", it.url.encodedPath)
            val json = ContractJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            assertEquals("Name", json["displayName"]!!.jsonPrimitive.content)
            respond(response(), HttpStatusCode.Created, jsonHeaders)
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            store.submit(AuthFormMode.REGISTER, "a@b.org", "abcdefghijkl", " Name "); runCurrent()
            assertEquals(user.stats, assertIs<AuthState.Authenticated>(store.state.value.session).user.stats)
            assertFalse(store.state.value.canAuthenticate)
        } finally { client.close() }
    }

    @Test fun httpErrorsAreTypedWithoutServerBodyAndCanRetry() = runTest {
        for (status in listOf(400, 401, 403, 409, 429, 503)) {
            val client = client(MemoryStore(source.baseUrl)) { addHandler {
                respond("secret-server-content", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.RetryAfter, "15"))
            } }
            try {
                val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
                store.submit(AuthFormMode.LOGIN, "a@b.org", "password", ""); runCurrent()
                assertEquals(AuthError.Http(status, 15), store.state.value.error)
                assertEquals(AuthState.SignedOut, store.state.value.session)
                assertTrue(store.state.value.canAuthenticate)
                assertFalse(store.state.value.toString().contains("secret-server-content"))
                store.dismissFeedback(); assertNull(store.state.value.error)
            } finally { client.close() }
        }
    }

    @Test fun offlineRestoreKeepsUncertainSessionAndRetryLoadsMe() = runTest {
        var offline = true
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        val client = client(disk) { addHandler {
            if (offline) throw IOException("private detail")
            respond(profileJson, headers = jsonHeaders)
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            assertEquals(AuthState.NotLoaded, store.state.value.session)
            assertEquals(AuthError.Network, store.state.value.error)
            assertFalse(store.state.value.canAuthenticate)
            assertNotNull(disk.value)
            offline = false; store.refresh(); runCurrent()
            assertIs<AuthState.Authenticated>(store.state.value.session)
        } finally { client.close() }
    }

    @Test fun refreshRevokedSessionClearsPreviousPrivateProfile() = runTest {
        var revoked = false
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        val client = client(disk) { addHandler {
            if (revoked) respond("", HttpStatusCode.Unauthorized) else respond(profileJson, headers = jsonHeaders)
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            assertIs<AuthState.Authenticated>(store.state.value.session)
            revoked = true; store.refresh(); runCurrent()
            assertEquals(AuthState.SignInRequired, store.state.value.session)
            assertTrue(store.state.value.canAuthenticate); assertNull(disk.value)
        } finally { client.close() }
    }

    @Test fun logoutImmediatelyHidesProfileAndOfflineResultIsExplicit() = runTest {
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        val wait = CompletableDeferred<Unit>()
        val client = client(disk) { addHandler {
            if (it.method == HttpMethod.Post) { wait.await(); throw IOException("offline") }
            respond(profileJson, headers = jsonHeaders)
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            store.logout(); assertEquals(AuthState.NotLoaded, store.state.value.session)
            assertFalse(store.state.value.canAuthenticate)
            runCurrent(); wait.complete(Unit); runCurrent()
            assertEquals(AuthState.SignedOut, store.state.value.session)
            assertEquals(AuthNotice.LOCAL_ONLY_LOGOUT, store.state.value.notice)
            assertNull(disk.value)
        } finally { client.close() }
    }

    @Test fun clearFailureNeverClaimsSuccessfulLogout() = runTest {
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        val client = client(disk) { addHandler {
            if (it.method == HttpMethod.Post) respond("", HttpStatusCode.NoContent)
            else respond(profileJson, headers = jsonHeaders)
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            disk.failClear = true; store.logout(); runCurrent()
            assertEquals(AuthState.StorageUnavailable, store.state.value.session)
            assertEquals(AuthError.Storage, store.state.value.error)
            assertNull(store.state.value.notice); assertFalse(store.state.value.canAuthenticate)
            disk.failClear = false; store.logout(); runCurrent()
            assertEquals(AuthState.SignedOut, store.state.value.session)
        } finally { client.close() }
    }

    @Test fun storageFailureCanBeClearedExplicitly() = runTest {
        val disk = MemoryStore(source.baseUrl).apply { failRead = true }
        val client = client(disk) { addHandler { error("Unexpected HTTP") } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            assertEquals(AuthState.StorageUnavailable, store.state.value.session)
            assertFalse(store.submit(AuthFormMode.LOGIN, "a@b.org", "pass", ""))
            store.logout(); runCurrent()
            assertEquals(AuthState.SignedOut, store.state.value.session)
            assertEquals(AuthNotice.LOCAL_ONLY_LOGOUT, store.state.value.notice)
        } finally { client.close() }
    }

    @Test fun accountSwitchNeverReusesPreviousProfile() = runTest {
        val second = profileJson.replace(user.id, "22222222-2222-4222-8222-222222222222").replace(user.displayName, "Second")
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        val client = client(disk) { addHandler {
            when (it.url.encodedPath) {
                "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                "/api/v1/auth/login" -> respond(response(second), headers = jsonHeaders)
                else -> respond(profileJson, headers = jsonHeaders)
            }
        } }
        try {
            val store = AuthStore(client, backgroundScope); store.start(); runCurrent()
            store.logout(); runCurrent()
            assertEquals(AuthNotice.LOGGED_OUT, store.state.value.notice)
            store.submit(AuthFormMode.LOGIN, "second@example.org", "password", ""); runCurrent()
            val actual = assertIs<AuthState.Authenticated>(store.state.value.session).user
            assertEquals("Second", actual.displayName); assertNotEquals(user.id, actual.id)
        } finally { client.close() }
    }

    @Test fun bootstrapStartsAuthWithoutWaitingForItsNetworkAndKeepsGuestOwner() = runTest {
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        val wait = CompletableDeferred<Unit>()
        val client = client(disk) { addHandler { wait.await(); respond(profileJson, headers = jsonHeaders) } }
        try {
            val auth = AuthStore(client, backgroundScope)
            var guestBytes: String? = null; var writes = 0
            val storage = object : GuestSnapshotStorage {
                override suspend fun read(): String? = guestBytes
                override suspend fun write(contents: String) { guestBytes = contents; writes++ }
            }
            val app = dev.hackatlas.mobile.ui.AppState(source, backgroundScope, { storage },
                catalogEnabled = false, auth = auth)
            app.load(); runCurrent()
            val ready = assertIs<dev.hackatlas.mobile.ui.BootstrapState.Ready>(app.state.value)
            assertEquals(AuthOperation.RESTORE, auth.state.value.busy)
            val original = guestBytes; val originalWrites = writes
            wait.complete(Unit); runCurrent()
            assertIs<AuthState.Authenticated>(auth.state.value.session)
            app.load(); runCurrent()
            assertSame(ready, app.state.value)
            assertEquals(original, guestBytes); assertEquals(originalWrites, writes)
        } finally { client.close() }
    }

    @Test fun backgroundInvalidatesFormMemoryAndForegroundRechecksRevocation() = runTest {
        val disk = MemoryStore(source.baseUrl).apply { value = session() }
        var calls = 0
        val client = client(disk) { addHandler {
            calls++
            if (calls > 1) respond("", HttpStatusCode.Unauthorized) else respond(profileJson, headers = jsonHeaders)
        } }
        try {
            val auth = AuthStore(client, backgroundScope); auth.start(); runCurrent()
            val epoch = auth.state.value.formRevision
            auth.onBackground(); assertTrue(auth.state.value.formRevision > epoch)
            auth.onForeground(); runCurrent()
            assertEquals(AuthState.SignInRequired, auth.state.value.session)
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun closingUiObserverDoesNotCancelProcessOwnedLogin() = runTest {
        val wait = CompletableDeferred<Unit>()
        val client = client(MemoryStore(source.baseUrl)) { addHandler { wait.await(); respond(response(), headers = jsonHeaders) } }
        try {
            val auth = AuthStore(client, backgroundScope); auth.start(); runCurrent()
            val observer = backgroundScope.launch { auth.state.collect {} }
            auth.submit(AuthFormMode.LOGIN, "a@b.org", "password", ""); runCurrent()
            observer.cancelAndJoin()
            wait.complete(Unit); runCurrent()
            assertIs<AuthState.Authenticated>(auth.state.value.session)
            assertNull(auth.state.value.busy)
        } finally { client.close() }
    }
}
