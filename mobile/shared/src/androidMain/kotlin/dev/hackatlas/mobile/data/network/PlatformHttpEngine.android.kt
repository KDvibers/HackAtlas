package dev.hackatlas.mobile.data.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createPlatformHttpEngine(): HttpClientEngine = OkHttp.create {
    config {
        followRedirects(false)
        followSslRedirects(false)
        // This engine serves public catalogue GETs. Recover stale pooled sockets
        // after network changes; user-visible HTTP errors still reach the stores.
        retryOnConnectionFailure(true)
    }
}
