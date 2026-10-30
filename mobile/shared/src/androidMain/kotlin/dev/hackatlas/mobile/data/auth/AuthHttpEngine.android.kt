package dev.hackatlas.mobile.data.auth

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createAuthHttpEngine(): HttpClientEngine = OkHttp.create {
    config {
        followRedirects(false)
        followSslRedirects(false)
        retryOnConnectionFailure(false)
    }
}
