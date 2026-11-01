package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlin.test.*

class AccountSavedClientTest {
    private val source = CatalogSource.Remote("https://saved.example.org/api/v1/")
    private val user = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private val page = CanonicalFixtures.files.getValue("catalog-page.json")
    private val id = ContractJson.decodeFromString<HackathonPageDto>(page).items.first().id
    private val token = "synthetic-saved-" + "s".repeat(40)
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private class Disk(override val apiBaseUrl: String, var session: StoredSession?) : SecureTokenStore {
        override suspend fun read() = session
        override suspend fun write(session: StoredSession) { this.session = session }
        override suspend fun clear() { session = null }
    }
    private fun disk(expires: String = "2026-12-01T00:00:00Z") = Disk(source.baseUrl, StoredSession(token, expires, user.id))
    private fun client(disk: Disk, handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        AuthSessionClient(MockEngine(handler), source, disk) { Instant.parse("2026-11-01T10:00:00Z") }

    @Test fun savedPageUsesFixedApiExpectedOwnerAndSingleBearer() = runTest {
        val c = client(disk()) {
            assertEquals("/api/v1/me/saved", it.url.encodedPath)
            assertEquals(HttpMethod.Get, it.method)
            assertEquals(setOf("page", "size"), it.url.parameters.names())
            assertEquals("0", it.url.parameters["page"]); assertEquals("20", it.url.parameters["size"])
            assertEquals(listOf("Bearer $token"), it.headers.getAll(HttpHeaders.Authorization))
            respond(page, headers = headers)
        }
        try { assertEquals(id, c.listSaved(user.id, 0, 20).items.first().id) } finally { c.close() }
    }

    @Test fun putAndDeleteRequire204AndDoNotRetry() = runTest {
        var calls = 0
        val c = client(disk()) {
            calls++; assertEquals("/api/v1/me/saved/$id", it.url.encodedPath)
            assertEquals(if(calls == 1) HttpMethod.Put else HttpMethod.Delete, it.method)
            assertTrue(it.url.parameters.isEmpty()); assertEquals("Bearer $token", it.headers[HttpHeaders.Authorization])
            respond("", HttpStatusCode.NoContent)
        }
        try { c.save(user.id, id); c.unsave(user.id, id); assertEquals(2, calls) } finally { c.close() }
    }

    @Test fun staleOwnerAndUnsafeIdNeverSendAnotherUsersBearer() = runTest {
        val c = client(disk()) { error("Must not send HTTP") }
        try {
            assertFailsWith<AuthException> { c.listSaved("22222222-2222-4222-8222-222222222222", 0, 20) }
            assertFailsWith<IllegalArgumentException> { c.save(user.id, "../auth/logout") }
            assertFailsWith<IllegalArgumentException> { c.listSaved(user.id, -1, 20) }
        } finally { c.close() }
    }

    @Test fun saved401InvalidatesCredentialAndLocalIdentity() = runTest {
        val disk = disk()
        val c = client(disk) { respond("private server text", HttpStatusCode.Unauthorized) }
        try {
            val error = assertFailsWith<AuthException> { c.listSaved(user.id, 0, 20) }
            assertEquals(AuthError.Unauthorized, error.error)
            assertEquals(AuthState.SignInRequired, c.state.value)
            assertNull(c.localIdentity.value); assertNull(disk.session)
        } finally { c.close() }
    }

    @Test fun offlineRestoreExposesOnlyValidatedNonsecretIdentity() = runTest {
        val c = client(disk()) { throw IOException("offline") }
        try {
            assertFailsWith<AuthException> { c.restore() }
            assertEquals(user.id, c.localIdentity.value?.userId)
            assertEquals(AuthState.NotLoaded, c.state.value)
            assertFalse(c.localIdentity.value.toString().contains(token))
        } finally { c.close() }
    }

    @Test fun expiredSessionCannotIdentifyOrReadPrivateCache() = runTest {
        val c = client(disk("2026-10-01T00:00:00Z")) { error("Must not send HTTP") }
        try {
            assertFailsWith<AuthException> { c.listSaved(user.id, 0, 20) }
            assertNull(c.localIdentity.value)
            assertEquals(AuthState.SignInRequired, c.state.value)
        } finally { c.close() }
    }

    @Test fun malformedPagesAndUnexpectedMutationStatusAreNotSuccess() = runTest {
        val c = client(disk()) { if (it.method == HttpMethod.Get) respond("{}", headers = headers) else respond("{}", HttpStatusCode.OK, headers) }
        try {
            assertEquals(AuthError.InvalidResponse, assertFailsWith<AuthException> { c.listSaved(user.id, 0, 20) }.error)
            assertIs<AuthError.Http>(assertFailsWith<AuthException> { c.save(user.id, id) }.error)
        } finally { c.close() }
    }

    @Test fun missingCredentialOnLaterRestoreDropsCachedIdentity() = runTest {
        val disk = disk()
        val c = client(disk) { respond(CanonicalFixtures.files.getValue("profile.json"), headers = headers) }
        try {
            c.restore(); assertNotNull(c.localIdentity.value)
            disk.session = null; c.restore()
            assertEquals(AuthState.SignedOut, c.state.value); assertNull(c.localIdentity.value)
        } finally { c.close() }
    }
}
