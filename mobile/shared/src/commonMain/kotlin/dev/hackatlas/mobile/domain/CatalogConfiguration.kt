package dev.hackatlas.mobile.domain

/** Invalid or absent configuration is visible in the UI, never an implicit DEMO fallback. */
fun selectCatalogSource(isDebug: Boolean, mode: String, baseUrl: String, allowDebugHttp: Boolean = false): CatalogSource? =
    when (mode) {
        "DEMO" -> CatalogSource.BundledDemo.takeIf { isDebug }
        "REMOTE" -> try { CatalogSource.Remote(baseUrl, allowDebugHttp = isDebug && allowDebugHttp) }
            catch (_: IllegalArgumentException) { null }
        else -> null
    }
