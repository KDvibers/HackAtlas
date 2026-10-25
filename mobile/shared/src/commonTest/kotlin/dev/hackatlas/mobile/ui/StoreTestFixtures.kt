package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.*

internal object StoreTestFixtures {
    val events = ContractJson.decodeFromString<HackathonPageDto>(CanonicalFixtures.files.getValue("catalog-page.json")).items.map { it.toDomain() }
    val detail = ContractJson.decodeFromString<HackathonDetailDto>(CanonicalFixtures.files.getValue("hackathon-detail.json")).toDomain()
    val metadata = ContractJson.decodeFromString<FilterMetadataDto>(CanonicalFixtures.files.getValue("filter-metadata.json")).toDomain()
    const val AS_OF = "2026-10-12T10:00:00Z"

    fun page(query: CatalogQuery = CatalogQuery(), items: List<Hackathon> = events.take(2),
             hasNext: Boolean = false, asOf: String = AS_OF): CatalogPage {
        val total = query.page * query.size + items.size + if (hasNext) query.size else 0
        return CatalogPage(items, query.page, query.size, total,
            if (total == 0) 0 else (total - 1) / query.size + 1, hasNext, asOf)
    }

    fun detail(id: String): HackathonDetail = detail.copy(hackathon = events.first { it.id == id })
}

internal class StoreTestRepository : CatalogRepository {
    val catalogCalls = mutableListOf<CatalogQuery>()
    val detailCalls = mutableListOf<String>()
    var metadataCalls = 0
    var onCatalog: suspend (CatalogQuery) -> CatalogPage = { StoreTestFixtures.page(it) }
    var onDetail: suspend (String) -> HackathonDetail = { StoreTestFixtures.detail(it) }
    var onFilters: suspend () -> FilterMetadata = { StoreTestFixtures.metadata }

    override suspend fun catalog(query: CatalogQuery): CatalogPage {
        catalogCalls += query
        return onCatalog(query)
    }
    override suspend fun detail(id: String): HackathonDetail {
        detailCalls += id
        return onDetail(id)
    }
    override suspend fun filters(): FilterMetadata {
        metadataCalls++
        return onFilters()
    }
}
