package dev.hackatlas.mobile.domain

/** Public catalogue only; implementations keep the selected source and server ordering intact. */
interface CatalogRepository {
    suspend fun catalog(query: CatalogQuery): CatalogPage
    suspend fun detail(id: String): HackathonDetail
    suspend fun filters(): FilterMetadata
}
