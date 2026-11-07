package dev.hackatlas.mobile.domain

enum class AccountAccessMode { LEGACY, N2_REQUIRED }

internal fun requireAccountAccessSource(source: CatalogSource?, accessMode: AccountAccessMode) {
    require(accessMode != AccountAccessMode.N2_REQUIRED || source is CatalogSource.Remote) {
        "Closed N2 requires an explicitly configured REMOTE source"
    }
}

/** Invalid or absent configuration is visible in the UI, never an implicit DEMO fallback. */
fun selectCatalogSource(isDebug: Boolean, mode: String, baseUrl: String, allowDebugHttp: Boolean = false): CatalogSource? =
    when (mode) {
        "DEMO" -> CatalogSource.BundledDemo.takeIf { isDebug }
        "REMOTE" -> try { CatalogSource.Remote(baseUrl, allowDebugHttp = isDebug && allowDebugHttp) }
            catch (_: IllegalArgumentException) { null }
        else -> null
    }
