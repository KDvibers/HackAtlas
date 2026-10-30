package dev.hackatlas.mobile.data.auth

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun createAuthHttpEngine(): HttpClientEngine = Darwin.create {
    configureSession {
        HTTPCookieStorage = null
        HTTPShouldSetCookies = false
        URLCache = null
    }
}
