package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.domain.CatalogSource
import io.ktor.client.engine.HttpClientEngine

internal expect fun createAuthHttpEngine(): HttpClientEngine

/** No bearer interceptor is installed on the public catalogue client. */
fun createAuthSessionClient(source: CatalogSource.Remote, store: SecureTokenStore): AuthSessionClient {
    require(source.baseUrl == store.apiBaseUrl) { "Auth store API scope mismatch" }
    return AuthSessionClient(createAuthHttpEngine(), source, store)
}
