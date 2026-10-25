package dev.hackatlas.mobile.domain

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import io.ktor.http.encodedPath

sealed interface CatalogSource {
    data object BundledDemo : CatalogSource

    /** Hosts must pass allowDebugHttp only for an explicitly configured development build. */
    class Remote(baseUrl: String, allowDebugHttp: Boolean = false) : CatalogSource {
        val baseUrl: String = normalize(baseUrl, allowDebugHttp)
    }
}

private fun normalize(raw: String, allowDebugHttp: Boolean): String {
    fun valid(condition: Boolean) = require(condition) { "Expected an absolute API base URL ending in /api/v1" }
    valid(Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw))
    valid(raw.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
    valid('?' !in raw && '#' !in raw)
    val authority = raw.substringAfter("://").substringBefore('/')
    valid(authority.isNotEmpty() && '@' !in authority)
    val url = try { Url(raw) } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid API base URL")
    }
    valid(url.host.isNotBlank() && url.port in 1..65535)
    valid(url.protocol.name == "https" || (allowDebugHttp && url.protocol.name == "http"))
    val path = url.encodedPath.removeSuffix("/")
    valid(path.endsWith("/api/v1"))
    valid(path.split('/').drop(1).all {
        val segment = it.decodeURLPart()
        segment.isNotEmpty() && segment != "." && segment != ".." && '/' !in segment && '\\' !in segment
    })
    return URLBuilder(url).apply {
        host = host.lowercase()
        encodedPath = "$path/"
    }.buildString()
}
