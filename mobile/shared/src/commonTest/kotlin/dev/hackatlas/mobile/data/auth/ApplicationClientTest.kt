package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

class ApplicationClientTest {
    private val source = CatalogSource.Remote("https://marks.example.org/api/v1/")
    private val user = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private val id = "39287368-5259-5235-a1c4-25b4e1e8421e"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val token = "synthetic-application-" + "a".repeat(40)
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val date = "2026-11-05T10:00:00Z"
    private val mark get() = """{"hackathonId":"$id","status":"APPLIED","selfReported":true,"markedAt":"$date"}"""
    private fun page(items: String = mark, total: Int = 1) =
        """{"items":[$items],"page":0,"size":20,"totalElements":$total,"totalPages":1,"hasNext":false,"asOf":"$date"}"""
    private class Disk(override val apiBaseUrl: String, var session: StoredSession?) : SecureTokenStore {
        override suspend fun read() = session
        override suspend fun write(session: StoredSession) { this.session = session }
        override suspend fun clear() { session = null }
    }
    private fun disk() = Disk(source.baseUrl, StoredSession(token, "2026-12-01T00:00:00Z", user.id))
    private fun client(disk: Disk = disk(), handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AuthSessionClient(MockEngine(handler), source, disk) { Instant.parse(date) }

    @Test fun listUsesOwnedBearerAndCanonicalPagination() = runTest {
        val c = client {
            assertEquals("marks.example.org", it.url.host)
            assertEquals("/api/v1/me/applications", it.url.encodedPath)
            assertEquals(HttpMethod.Get, it.method)
            assertEquals(setOf("page", "size"), it.url.parameters.names())
            assertEquals("0", it.url.parameters["page"]); assertEquals("20", it.url.parameters["size"])
            assertEquals(listOf("Bearer $token"), it.headers.getAll(HttpHeaders.Authorization))
            respond(page(), headers = headers)
        }
        try { assertEquals(id, c.listApplications(user.id, 0, 20).items.single().hackathonId) } finally { c.close() }
    }

    @Test fun explicitPutReturnsOriginalMarkedAtAndDeleteRequires204() = runTest {
        var calls = 0
        val c = client {
            calls++
            assertEquals("/api/v1/me/applications/$id", it.url.encodedPath)
            assertEquals(if(calls <= 2) HttpMethod.Put else HttpMethod.Delete, it.method)
            assertEquals("Bearer $token", it.headers[HttpHeaders.Authorization])
            assertTrue(it.url.parameters.isEmpty())
            if(calls <= 2) respond(mark, headers = headers) else respond("", HttpStatusCode.NoContent)
        }
        try {
            assertEquals(date, c.markApplied(user.id, id).markedAt)
            assertEquals(date, c.markApplied(user.id, id).markedAt)
            c.removeApplication(user.id, id); assertEquals(3, calls)
        } finally { c.close() }
    }

    @Test fun uuidCaseDoesNotMakeTheServerMarkBelongToAnotherEvent() = runTest {
        val c = client { respond(mark, headers = headers) }
        try { assertEquals(id, c.markApplied(user.id, id.uppercase()).hackathonId) } finally { c.close() }
    }

    @Test fun falseOrMalformedOrForeignMarkNeverBecomesApplied() = runTest {
        for(body in listOf(mark.replace("APPLIED", "ACCEPTED"), mark.replace("true", "false"),
            mark.replace(date, "invalid"), mark.replace(id, "../auth/logout"), mark.replace(id, other))) {
            val c = client { respond(body, headers = headers) }
            try { assertEquals(AuthError.InvalidResponse, assertFailsWith<AuthException> { c.markApplied(user.id, id) }.error) }
            finally { c.close() }
        }
    }

    @Test fun incompleteOrInconsistentPagesAreInvalidResponses() = runTest {
        for(body in listOf(page().replace("\"page\":0", "\"page\":1"),
            page().replace("\"size\":20", "\"size\":0"), page().replace("\"hasNext\":false", "\"hasNext\":true"),
            page().replace("\"totalPages\":1", "\"totalPages\":2"), page().replace("\"asOf\":\"$date\"", "\"asOf\":\"invalid\""),
            page("$mark,$mark", 2), page().replace("\"selfReported\":true,", ""))) {
            val c = client { respond(body, headers = headers) }
            try { assertEquals(AuthError.InvalidResponse, assertFailsWith<AuthException> { c.listApplications(user.id, 0, 20) }.error) }
            finally { c.close() }
        }
    }

    @Test fun emptyAndOverflowSafePagesKeepCanonicalMetadata() = runTest {
        val c = client { respond(page("", 0).replace("\"totalPages\":1", "\"totalPages\":0"), headers = headers) }
        try { assertTrue(c.listApplications(user.id, 0, 20).items.isEmpty()) } finally { c.close() }
        val overflow = client { respond(page().replace("\"totalElements\":1", "\"totalElements\":2147483647")
            .replace("\"totalPages\":1", "\"totalPages\":107374183").replace("\"hasNext\":false", "\"hasNext\":true"), headers = headers) }
        try { assertEquals(107374183, overflow.listApplications(user.id, 0, 20).totalPages) } finally { overflow.close() }
    }

    @Test fun wrongOwnerUnsafeIdAndInvalidPageNeverSendBearer() = runTest {
        val c = client { error("No HTTP allowed") }
        try {
            assertFailsWith<AuthException> { c.listApplications(other, 0, 20) }
            assertFailsWith<IllegalArgumentException> { c.markApplied(user.id, "../me") }
            assertFailsWith<IllegalArgumentException> { c.removeApplication(user.id, "bad") }
            assertFailsWith<IllegalArgumentException> { c.listApplications(user.id, -1, 20) }
        } finally { c.close() }
        val absent = client(Disk(source.baseUrl, null)) { error("No HTTP allowed") }
        try { assertFailsWith<AuthException> { absent.markApplied(user.id, id) } } finally { absent.close() }
    }

    @Test fun unauthorizedOnAnyOperationClearsSession() = runTest {
        for(op in 0..2) {
            val disk = disk();val c = client(disk) { respond("private body", HttpStatusCode.Unauthorized) }
            try {
                assertEquals(AuthError.Unauthorized, assertFailsWith<AuthException> {
                    when(op) { 0 -> c.listApplications(user.id); 1 -> c.markApplied(user.id, id); else -> c.removeApplication(user.id, id) }
                }.error)
                assertNull(disk.session);assertNull(c.localIdentity.value);assertEquals(AuthState.SignInRequired, c.state.value)
            } finally { c.close() }
        }
    }

    @Test fun rateLimitDoesNotRetryAndPreservesDelay() = runTest {
        var calls = 0
        val c = client { calls++;respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7")) }
        try {
            assertEquals(AuthError.Http(429, 7), assertFailsWith<AuthException> { c.markApplied(user.id, id) }.error)
            assertEquals(1, calls)
        } finally { c.close() }
    }

    @Test fun cancellationIsNotConvertedToAnAuthOrSuccessResult() = runTest {
        val c = client { throw CancellationException("Canceled") }
        try { assertFailsWith<CancellationException> { c.markApplied(user.id, id) } } finally { c.close() }
    }
}
