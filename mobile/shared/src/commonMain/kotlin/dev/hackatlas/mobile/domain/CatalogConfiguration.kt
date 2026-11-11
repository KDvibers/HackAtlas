package dev.hackatlas.mobile.domain

enum class AccountAccessMode { LEGACY, N2_REQUIRED }

data class CatalogRuntimeConfiguration(
    val source: CatalogSource?,
    val accessMode: AccountAccessMode = AccountAccessMode.LEGACY,
)

fun selectCatalogRuntimeConfiguration(
    isDebug: Boolean,
    sourceMode: String,
    baseUrl: String,
    accountMode: String = "LEGACY",
    allowDebugHttp: Boolean = false,
): CatalogRuntimeConfiguration {
    val source = selectCatalogSource(isDebug, sourceMode, baseUrl, allowDebugHttp)
    if (!isDebug || accountMode == "LEGACY") return CatalogRuntimeConfiguration(source)
    if (accountMode == "N2_REQUIRED" && source is CatalogSource.Remote) {
        return CatalogRuntimeConfiguration(source, AccountAccessMode.N2_REQUIRED)
    }
    return CatalogRuntimeConfiguration(null)
}

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
