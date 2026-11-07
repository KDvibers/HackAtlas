package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ContractJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.http.headers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
internal data class N2CapturedResponse(
    val method: String,
    val operationPath: String,
    val status: Int,
    val headers: Map<String, String>,
    val contentType: String,
    val cacheControl: String,
    val retryAfter: String,
    val body: JsonObject?,
    val caseId: String? = null,
    val allResponsesIndex: Int? = null,
) {
    val requestMethod get() = HttpMethod.parse(method.uppercase())
    val requestPath get() = operationPath.replace("{id}", "11111111-1111-4111-8111-111111111111")
    val code get() = body?.get("code")?.jsonPrimitive?.content
}

@Serializable
internal data class N2CapturedSequence(val caseIds: List<String>, val sameBearer: Boolean)

@Serializable
internal data class N2CapturedExport(
    val candidateVersion: String,
    val responses: List<N2CapturedResponse>,
    val responseCount: Int? = null,
    val sequences: List<N2CapturedSequence> = emptyList(),
)

internal object N2HttpFixtures {
    val full = ContractJson.decodeFromString<N2CapturedExport>(N2FixtureFiles.files.getValue("all-responses.json"))
    val deletePassword = ContractJson.decodeFromString<N2CapturedExport>(
        N2FixtureFiles.files.getValue("delete-password-same-session.json"),
    )

    fun response(path: String, status: Int, matching: (N2CapturedResponse) -> Boolean = { true }) =
        full.responses.first { it.operationPath == path && it.status == status && matching(it) }

    fun deletePasswordPair(sequence: N2CapturedSequence) = sequence.caseIds.map { id ->
        deletePassword.responses.single { it.caseId == id }
    }
}

internal fun MockRequestHandleScope.respondWith(capture: N2CapturedResponse, body: JsonElement? = capture.body) =
    respond(
        content = body?.toString().orEmpty(),
        status = HttpStatusCode.fromValue(capture.status),
        headers = headers { capture.headers.forEach { (name, value) -> append(name, value) } },
    )

class N2HttpFixturesTest {
    @Test
    fun deletePasswordPairsReferenceOriginalCapturedResponses() {
        val full = N2HttpFixtures.full
        val pairs = N2HttpFixtures.deletePassword
        assertEquals("0.2.0-test.1", full.candidateVersion)
        assertEquals(full.candidateVersion, pairs.candidateVersion)
        assertEquals(127, full.responseCount)
        assertEquals(127, full.responses.size)
        assertEquals(4, pairs.responses.size)
        assertEquals(2, pairs.sequences.size)
        assertEquals(4, pairs.sequences.flatMap { it.caseIds }.toSet().size)
        assertEquals(4, pairs.responses.map { it.allResponsesIndex }.toSet().size)

        val flags = pairs.sequences.map { sequence ->
            assertTrue(sequence.sameBearer)
            val (error, profile) = N2HttpFixtures.deletePasswordPair(sequence)
            assertEquals("/me/delete-account", error.operationPath)
            assertEquals(401, error.status)
            assertEquals("INVALID_CREDENTIALS", error.code)
            assertEquals("/me", profile.operationPath)
            assertEquals(200, profile.status)
            for (row in listOf(error, profile)) {
                val original = full.responses[assertNotNull(row.allResponsesIndex)]
                assertEquals(original, row.copy(caseId = null, allResponsesIndex = null))
            }
            profile.body?.getValue("emailVerified")?.jsonPrimitive?.boolean
        }
        assertEquals(setOf(false, true), flags.toSet())
    }

    @Test
    fun mockEngineReplaysAllCapturedStatusesHeadersAndBodies() = runTest {
        val captures = N2HttpFixtures.full.responses
        var index = 0
        val engine = MockEngine { request ->
            val capture = captures[index++]
            assertEquals(capture.requestMethod, request.method)
            assertEquals("/api/v1${capture.requestPath}", request.url.encodedPath)
            respondWith(capture)
        }
        val client = HttpClient(engine) { expectSuccess = false }
        try {
            for (capture in captures) {
                val response = client.request("https://n2.example.org/api/v1${capture.requestPath}") {
                    method = capture.requestMethod
                }
                assertEquals(capture.status, response.status.value)
                capture.headers.forEach { (name, value) -> assertEquals(value, response.headers[name]) }
                assertEquals(capture.contentType, response.headers["Content-Type"].orEmpty())
                assertEquals(capture.cacheControl, response.headers["Cache-Control"].orEmpty())
                assertEquals(capture.retryAfter, response.headers["Retry-After"].orEmpty())
                val requestId = capture.body?.get("requestId")?.jsonPrimitive?.content
                if (requestId != null) assertEquals(requestId, response.headers["X-Request-Id"])
                val body = response.bodyAsText()
                if (capture.status == 204) assertEquals("", body)
                else assertEquals<JsonElement?>(capture.body, ContractJson.parseToJsonElement(body))
            }
            assertEquals(captures.size, index)
        } finally {
            client.close()
            engine.close()
        }
    }
}
