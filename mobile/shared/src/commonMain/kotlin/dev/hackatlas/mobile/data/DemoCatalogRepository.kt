package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*

/** Explicitly selected bundled data; never a fallback for a failed remote request. */
class DemoCatalogRepository(private val delegate: DemoRepository) : CatalogRepository {
    override suspend fun catalog(query: CatalogQuery): CatalogPage = delegate.catalog(query)
    override suspend fun detail(id: String): HackathonDetail =
        delegate.detail(id) ?: throw CatalogException(CatalogError.NotFound)
    override suspend fun filters(): FilterMetadata = delegate.metadata
}
