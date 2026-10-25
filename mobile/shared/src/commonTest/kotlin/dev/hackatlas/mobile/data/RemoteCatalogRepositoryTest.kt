package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.data.network.createApiClient
import dev.hackatlas.mobile.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class RemoteCatalogRepositoryTest {
    private val eventId = "39287368-5259-5235-a1c4-25b4e1e8421e"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun fixture(name: String) = CanonicalFixtures.files.getValue(name)

    private suspend fun withApi(
        handler: MockRequestHandler,
        source: CatalogSource.Remote = CatalogSource.Remote("https://api.example.test/api/v1"),
        check: suspend (RemoteCatalogRepository) -> Unit,
    ) {
        val engine = MockEngine(handler)
        val client = createApiClient(engine, source)
        try { check(RemoteCatalogRepository(client, source)) }
        finally { client.close(); engine.close() }
    }

    @Test fun encodesCanonicalQuery() = runTest {
        withApi({ request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/hackathons", request.url.encodedPath)
            assertEquals(setOf("query", "format", "countryCode", "sort", "page", "size"), request.url.parameters.names())
            assertEquals("AI & ML", request.url.parameters["query"])
            assertEquals("ONLINE", request.url.parameters["format"])
            assertEquals("KZ", request.url.parameters["countryCode"])
            assertEquals("DEADLINE_ASC", request.url.parameters["sort"])
            assertEquals("0", request.url.parameters["page"])
            assertEquals("20", request.url.parameters["size"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(fixture("catalog-page.json"), headers = jsonHeaders)
        }) { api ->
            api.catalog(CatalogQuery(query = " AI & ML ", format = EventFormat.ONLINE, countryCode = "KZ"))
        }
    }

    @Test fun keepsAllAndFiltersAndFalseWithoutDroppingApiPrefix() = runTest {
        withApi({ request ->
            assertEquals("/gateway/api/v1/hackathons", request.url.encodedPath)
            assertNull(request.url.parameters["query"])
            assertEquals("Алматы & Астана", request.url.parameters["city"])
            assertEquals("ai-ml", request.url.parameters["topic"])
            assertEquals("OPEN", request.url.parameters["registrationStatus"])
            assertEquals("false", request.url.parameters["individualApplicationAllowed"])
            assertEquals("2026-11-01", request.url.parameters["startsFrom"])
            assertEquals("2026-12-31", request.url.parameters["startsTo"])
            assertEquals("START_ASC", request.url.parameters["sort"])
            assertEquals("2", request.url.parameters["page"])
            assertEquals("50", request.url.parameters["size"])
            respond(fixture("catalog-empty.json"), headers = jsonHeaders)
        }, CatalogSource.Remote("https://api.example.test/gateway/api/v1/")) { api ->
            api.catalog(CatalogQuery(query = "  ", city = "Алматы & Астана", topic = "ai-ml",
                registrationStatus = RegistrationStatus.OPEN, individualApplicationAllowed = false,
                startsFrom = "2026-11-01", startsTo = "2026-12-31", sort = CatalogSort.START_ASC, page = 2, size = 50))
        }
    }

    @Test fun rejectsUnsupportedFormatBeforeSendingRequest() = runTest {
        var requests = 0
        withApi({ requests++; respond(fixture("catalog-page.json"), headers = jsonHeaders) }) { api ->
            assertFailsWith<IllegalArgumentException> {
                api.catalog(CatalogQuery(format = EventFormat.UNSUPPORTED))
            }
            assertEquals(0, requests)
        }
    }

    @Test fun rejectsUnsupportedRegistrationStatusBeforeSendingRequest() = runTest {
        var requests = 0
        withApi({ requests++; respond(fixture("catalog-page.json"), headers = jsonHeaders) }) { api ->
            assertFailsWith<IllegalArgumentException> {
                api.catalog(CatalogQuery(registrationStatus = RegistrationStatus.UNSUPPORTED))
            }
            assertEquals(0, requests)
        }
    }

    @Test fun decodesCanonicalPageWithoutInventingValues() = runTest {
        withApi({ respond(fixture("catalog-page.json"), headers = jsonHeaders) }) { api ->
            val page = api.catalog(CatalogQuery())
            assertEquals(0, page.page)
            assertEquals(20, page.size)
            assertEquals(5, page.totalElements)
            assertEquals(1, page.totalPages)
            assertFalse(page.hasNext)
            assertEquals("2026-10-12T10:00:00Z", page.asOf)
            assertEquals(5, page.items.size)
            assertEquals(eventId, page.items.first().id)
            assertEquals("10000.00", page.items.first().prize.totalAmount)
            assertTrue(page.items.all { it.isDemo })
            assertEquals(TimePrecision.DATE, page.items[1].starts?.precision)
            assertNull(page.items[1].starts?.time)
            assertNull(page.items[1].starts?.instant)
            assertNull(page.items[3].prize.totalAmount)
            assertNull(page.items[3].registrationDeadline)
        }
    }

    @Test fun emptyResponseStaysEmptyInsteadOfLoadingDemo() = runTest {
        withApi({ respond(fixture("catalog-empty.json"), headers = jsonHeaders) }) { api ->
            val page = api.catalog(CatalogQuery())
            assertTrue(page.items.isEmpty())
            assertEquals(0, page.totalElements)
            assertEquals(0, page.totalPages)
            assertFalse(page.hasNext)
        }
    }

    @Test fun detailsAndMetadataUseCanonicalRoutes() = runTest {
        val paths = mutableListOf<String>()
        withApi({ request ->
            paths += request.url.encodedPath
            respond(fixture(if (request.url.encodedPath.endsWith("/filters")) "filter-metadata.json" else "hackathon-detail.json"), headers = jsonHeaders)
        }) { api ->
            val detail = api.detail(eventId)
            assertEquals(eventId, detail.hackathon.id)
            assertEquals("2026-11-01T13:00:00Z", detail.hackathon.registrationDeadline?.instant)
            assertEquals(emptyList(), detail.eligibility.allowedCountryCodes)
            assertEquals(false, detail.eligibility.studentsOnly)
            assertTrue(api.filters().formats.any { it.id == "ONLINE" })
            assertEquals(listOf("/api/v1/hackathons/$eventId", "/api/v1/metadata/filters"), paths)
        }
    }

    @Test fun preservesRealClassificationAndUnknownEnums() = runTest {
        val dto = ContractJson.decodeFromString<HackathonPageDto>(fixture("catalog-page.json"))
        val changed = dto.copy(items = listOf(dto.items.first().copy(isDemo = false, format = "FUTURE_FORMAT")))
        val json = JsonObject(ContractJson.parseToJsonElement(ContractJson.encodeToString(changed)).jsonObject + ("futureField" to JsonPrimitive(42)))
        withApi({ respond(json.toString(), headers = jsonHeaders) }) { api ->
            val event = api.catalog(CatalogQuery()).items.single()
            assertFalse(event.isDemo)
            assertEquals(EventFormat.UNSUPPORTED, event.format)
            assertTrue("format=FUTURE_FORMAT" in event.contractWarnings)
        }
    }

    @Test fun mapsProblemAnd404() = runTest {
        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)) {
            withApi({ respond(fixture("validation-error.json"), status, headersOf(HttpHeaders.ContentType, "application/problem+json")) }) { api ->
                val error = assertFailsWith<CatalogException> { api.detail(eventId) }.error
                if (status == HttpStatusCode.NotFound) assertEquals(CatalogError.NotFound, error)
                else assertEquals(CatalogError.Http(400, "demo-request-001"), error)
            }
        }
    }

    @Test fun mapsNonJsonServerErrorWithoutExposingBody() = runTest {
        withApi({ respond("<html>internal server diagnostic</html>", HttpStatusCode.InternalServerError,
            headersOf(HttpHeaders.ContentType to listOf("text/html"), "X-Request-Id" to listOf("server-001"))) }) { api ->
            val error = assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }
            assertEquals(CatalogError.Http(500, "server-001"), error.error)
            assertFalse(error.toString().contains("internal server diagnostic"))
        }
    }

    @Test fun redirectsAreErrorsAndNeverContactAnotherHost() = runTest {
        val hosts = mutableListOf<String>()
        withApi({ request ->
            hosts += request.url.host
            if (hosts.size == 1) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example.test/data"))
            else respond(fixture("catalog-page.json"), headers = jsonHeaders)
        }) { api ->
            assertEquals(CatalogError.Http(302, null), assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }.error)
            assertEquals(listOf("api.example.test"), hosts)
        }
    }

    @Test fun rejectsInvalidPayloadWithoutDemoFallback() = runTest {
        for (body in listOf("{", "{}", "<html>not JSON</html>")) {
            withApi({ respond(body, headers = jsonHeaders) }) { api ->
                assertEquals(CatalogError.InvalidResponse, assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }.error)
            }
        }
    }

    @Test fun rejectsUnexpectedSuccessContentType() = runTest {
        withApi({ respond(fixture("catalog-page.json"), headers = headersOf(HttpHeaders.ContentType, "text/html")) }) { api ->
            assertEquals(CatalogError.InvalidResponse, assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }.error)
        }
    }

    @Test fun rejectsInconsistentExactDate() = runTest {
        val dto = ContractJson.decodeFromString<HackathonDetailDto>(fixture("hackathon-detail.json"))
        val body = ContractJson.encodeToString(dto.copy(starts = dto.starts!!.copy(instant = "2026-11-12T10:00:00Z")))
        withApi({ respond(body, headers = jsonHeaders) }) { api ->
            assertEquals(CatalogError.InvalidResponse, assertFailsWith<CatalogException> { api.detail(eventId) }.error)
        }
    }

    @Test fun rejectsNegativePagination() = runTest {
        val dto = ContractJson.decodeFromString<HackathonPageDto>(fixture("catalog-page.json"))
        withApi({ respond(ContractJson.encodeToString(dto.copy(page = -1)), headers = jsonHeaders) }) { api ->
            assertEquals(CatalogError.InvalidResponse, assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }.error)
        }
    }

    @Test fun rejectsDetailForAnotherId() = runTest {
        withApi({ respond(fixture("hackathon-detail.json"), headers = jsonHeaders) }) { api ->
            assertEquals(CatalogError.InvalidResponse, assertFailsWith<CatalogException> {
                api.detail("9756e7f3-5796-5d69-93cf-a6a6cb8ebd33")
            }.error)
        }
    }

    @Test fun idsCannotEscapeTheDetailEndpoint() = runTest {
        var requests = 0
        withApi({ requests++; respond(fixture("hackathon-detail.json"), headers = jsonHeaders) }) { api ->
            assertFailsWith<IllegalArgumentException> { api.detail("../metadata/filters") }
            assertEquals(0, requests)
        }
    }

    @Test fun mapsTransportFailureWithoutRetryOrDemoFallback() = runTest {
        var requests = 0
        withApi({ requests++; throw IOException("transport unavailable") }) { api ->
            assertEquals(CatalogError.Network, assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }.error)
            assertEquals(1, requests)
        }
    }

    @Test fun mapsRequestTimeout() = runTest {
        withApi({ request -> throw HttpRequestTimeoutException(request) }) { api ->
            assertEquals(CatalogError.Timeout, assertFailsWith<CatalogException> { api.catalog(CatalogQuery()) }.error)
        }
    }

    @Test fun preservesCancellation() = runTest {
        withApi({ throw CancellationException("caller cancelled") }) { api ->
            assertEquals("caller cancelled", assertFailsWith<CancellationException> { api.catalog(CatalogQuery()) }.message)
        }
    }
}
