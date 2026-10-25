package dev.hackatlas.mobile.data.network

import io.ktor.client.engine.HttpClientEngine

internal expect fun createPlatformHttpEngine(): HttpClientEngine
