package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.domain.*

data class CatalogUiState(
    val query: CatalogQuery = CatalogQuery(),
    val items: List<Hackathon> = emptyList(),
    val metadata: FilterMetadata? = null,
    val nextPage: Int? = null,
    val asOf: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val metadataLoading: Boolean = true,
    val error: CatalogError? = null,
    val appendError: CatalogError? = null,
    val metadataError: CatalogError? = null,
) {
    /** A successful server response is required before the UI can show an empty result. */
    val isEmpty: Boolean get() = asOf != null && !loading && error == null && items.isEmpty()
}
