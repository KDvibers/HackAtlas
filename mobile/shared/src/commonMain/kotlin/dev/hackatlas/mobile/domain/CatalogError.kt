package dev.hackatlas.mobile.domain

sealed interface CatalogError {
    data object Network : CatalogError
    data object Timeout : CatalogError
    data object NotFound : CatalogError
    data class Http(val status: Int, val requestId: String?) : CatalogError
    data object InvalidResponse : CatalogError
}

/** Raw response bodies and transport diagnostics are not exposed as user-facing errors. */
class CatalogException(val error: CatalogError) : Exception("Catalog request failed")
