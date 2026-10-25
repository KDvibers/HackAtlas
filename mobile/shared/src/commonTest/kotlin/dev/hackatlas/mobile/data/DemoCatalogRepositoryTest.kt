package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DemoCatalogRepositoryTest {
    @Test fun asyncAdapterPreservesExplicitDemoAndFiltering() = runTest {
        val delegate = DemoRepository.load(FixtureSource { CanonicalFixtures.files.getValue(it) })
        val api: CatalogRepository = DemoCatalogRepository(delegate)
        val page = api.catalog(CatalogQuery(query = "AI Sprint"))
        assertEquals("AI Sprint 2026", page.items.single().title)
        assertTrue(page.items.single().isDemo)
        assertEquals("2026-10-12T10:00:00Z", page.asOf)
        assertEquals("10000.00", api.detail(page.items.single().id).hackathon.prize.totalAmount)
        assertTrue(api.filters().formats.any { it.id == "ONLINE" })
    }

    @Test fun missingDemoDetailUsesTheSameNotFoundContract() = runTest {
        val api = DemoCatalogRepository(DemoRepository.load(FixtureSource { CanonicalFixtures.files.getValue(it) }))
        assertEquals(CatalogError.NotFound, assertFailsWith<CatalogException> {
            api.detail("00000000-0000-0000-0000-000000000000")
        }.error)
    }
}
