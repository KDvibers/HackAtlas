package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** Borrows a client configured by createApiClient; it does not own or close the client. */
class RemoteCatalogRepository(
    private val client: HttpClient,
    private val source: CatalogSource.Remote,
) : CatalogRepository {
    override suspend fun catalog(query: CatalogQuery): CatalogPage {
        require(query.format != EventFormat.UNSUPPORTED) { "Unsupported format is not an API filter" }
        require(query.registrationStatus != RegistrationStatus.UNSUPPORTED) { "Unsupported registration status is not an API filter" }
        return get<HackathonPageDto, CatalogPage>("hackathons", {
            fun optional(name: String, value: String?) {
                value?.trim()?.takeIf { it.isNotEmpty() }?.let { parameter(name, it) }
            }
            optional("query", query.query)
            optional("format", query.format?.name)
            optional("countryCode", query.countryCode)
            optional("city", query.city)
            optional("topic", query.topic)
            optional("registrationStatus", query.registrationStatus?.name)
            query.individualApplicationAllowed?.let { parameter("individualApplicationAllowed", it) }
            optional("startsFrom", query.startsFrom)
            optional("startsTo", query.startsTo)
            parameter("sort", query.sort.name)
            parameter("page", query.page)
            parameter("size", query.size)
        }) { dto ->
            require(dto.page >= 0 && dto.size in 1..50 && dto.totalElements >= 0 && dto.totalPages >= 0)
            require(dto.items.size <= dto.size && dto.items.size <= dto.totalElements)
            Instant.parse(dto.asOf)
            CatalogPage(dto.items.map { it.toDomain() }, dto.page, dto.size, dto.totalElements,
                dto.totalPages, dto.hasNext, dto.asOf)
        }
    }

    override suspend fun detail(id: String): HackathonDetail {
        require(UUID.matches(id)) { "Expected a hackathon UUID" }
        return get<HackathonDetailDto, HackathonDetail>("hackathons/$id") { dto ->
            require(dto.id.equals(id, ignoreCase = true))
            dto.toDomain()
        }
    }

    override suspend fun filters(): FilterMetadata =
        get<FilterMetadataDto, FilterMetadata>("metadata/filters") { it.toDomain() }

    private suspend inline fun <reified T : Any, R> get(
        path: String,
        crossinline parameters: HttpRequestBuilder.() -> Unit = {},
        map: (T) -> R,
    ): R = transport {
        val response = client.get(source.baseUrl + path) { parameters() }
        if (!response.status.isSuccess()) throw CatalogException(response.catalogError())
        val dto = try {
            response.body<T>()
        } catch (_: ContentConvertException) {
            throw CatalogException(CatalogError.InvalidResponse)
        } catch (_: SerializationException) {
            throw CatalogException(CatalogError.InvalidResponse)
        } catch (_: NoTransformationFoundException) {
            throw CatalogException(CatalogError.InvalidResponse)
        }
        try {
            map(dto)
        } catch (_: IllegalArgumentException) {
            throw CatalogException(CatalogError.InvalidResponse)
        }
    }

    private suspend fun HttpResponse.catalogError(): CatalogError {
        if (status == HttpStatusCode.NotFound) return CatalogError.NotFound
        val problem = try {
            ContractJson.decodeFromString<ProblemDto>(bodyAsText())
        } catch (_: SerializationException) {
            null
        }
        return CatalogError.Http(status.value, problem?.requestId ?: headers["X-Request-Id"])
    }

    private suspend inline fun <T> transport(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        throw CatalogException(CatalogError.Timeout)
    } catch (_: ConnectTimeoutException) {
        throw CatalogException(CatalogError.Timeout)
    } catch (_: SocketTimeoutException) {
        throw CatalogException(CatalogError.Timeout)
    } catch (_: IOException) {
        throw CatalogException(CatalogError.Network)
    }

    private companion object {
        val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
