package dev.hackatlas.mobile.data.network

import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json

/** The caller owns both this client and the supplied engine and closes them at host shutdown. */
fun createApiClient(engine: HttpClientEngine, source: CatalogSource.Remote): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(ContentNegotiation) { json(ContractJson) }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
    defaultRequest {
        url(source.baseUrl)
        accept(ContentType.Application.Json)
    }
}
