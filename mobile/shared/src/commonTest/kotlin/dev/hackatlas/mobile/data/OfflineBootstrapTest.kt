package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.ui.AppState
import dev.hackatlas.mobile.ui.BootstrapState
import dev.hackatlas.mobile.ui.DetailUiState
import dev.hackatlas.mobile.ui.StoreTestFixtures
import dev.hackatlas.mobile.ui.StoreTestRepository
import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfflineBootstrapTest {
    @Test fun remotePageSizeReachesInitialRequestPaginationAndFilterReset() = runTest {
        val events = StoreTestFixtures.events
        val remote = StoreTestRepository().apply {
            onCatalog = { query -> StoreTestFixtures.page(query,
                events.drop(query.page * query.size).take(query.size),
                hasNext = (query.page + 1) * query.size < events.size) }
        }
        val state = AppState(CatalogSource.Remote("https://api.example.test/api/v1/"), backgroundScope,
            { TestGuestStorage() }, { remote }, remotePageSize = 2)
        state.load()
        runCurrent()
        val catalog = assertIs<BootstrapState.Ready>(state.state.value).catalog
        assertEquals(2, catalog.state.value.items.size)
        catalog.loadNext()
        runCurrent()
        assertEquals(listOf(0, 1), remote.catalogCalls.map { it.page })
        assertEquals(events.take(4), catalog.state.value.items)
        catalog.setQuery(catalog.state.value.query.copy(countryCode = "KZ"))
        runCurrent()
        assertEquals(listOf(0, 1, 0), remote.catalogCalls.map { it.page })
        assertTrue(remote.catalogCalls.all { it.size == 2 })
    }

    @Test fun remotePageSizeDoesNotChangeBundledDefaults() = runTest {
        val state = AppState(CatalogSource.BundledDemo, backgroundScope, { TestGuestStorage() },
            fixtureSource = FixtureSource { CanonicalFixtures.files.getValue(it) }, remotePageSize = 2)
        state.load()
        runCurrent()
        val catalog = assertIs<BootstrapState.Ready>(state.state.value).catalog.state.value
        assertEquals(20, catalog.query.size)
        assertEquals(CatalogSort.NEWEST, catalog.query.sort)
        assertTrue(catalog.items.size > 2)
    }

    @Test fun savedDetailsRemainAvailableWhenCatalogCannotLoad() = runTest {
        val source = FixtureSource { CanonicalFixtures.files.getValue(it) }
        val disk = TestGuestStorage()
        val first = demo(disk, source)
        first.load()
        val original = assertIs<BootstrapState.Ready>(first.state.value).saved.entries.value
        assertEquals(3, original.size)
        val restored = demo(disk, FixtureSource { error("Catalog unavailable") })
        restored.load()
        val result = assertIs<BootstrapState.Ready>(restored.state.value)
        assertNull(result.repository)
        assertEquals(original, result.saved.entries.value)
    }

    @Test fun corruptedStorageIsExplicitFailureAndNeverGetsReset() = runTest {
        val disk = TestGuestStorage("broken")
        val state = demo(disk)
        state.load()
        assertIs<BootstrapState.StorageFailed>(state.state.value)
        assertEquals("broken", disk.value)
        assertEquals(0, disk.writes)
    }

    @Test fun hostRecreationReusesReadyRepository() = runTest {
        val state = demo(TestGuestStorage())
        state.load()
        val first = assertIs<BootstrapState.Ready>(state.state.value)
        state.load()
        assertSame(first.saved, assertIs<BootstrapState.Ready>(state.state.value).saved)
        assertSame(first.catalog, assertIs<BootstrapState.Ready>(state.state.value).catalog)
    }

    @Test fun bundledCatalogKeepsApprovedInitialOrdering() = runTest {
        val app = demo(TestGuestStorage())
        app.load()
        runCurrent()
        val ready = assertIs<BootstrapState.Ready>(app.state.value)
        assertEquals(CatalogSort.NEWEST, ready.catalog.state.value.query.sort)
        assertEquals("AI Sprint 2026", ready.catalog.state.value.items.first().title)
    }

    @Test fun coldStartSavedWorksWhileCatalogNeverReturns() = runTest {
        val disk = TestGuestStorage()
        val source = CatalogSource.Remote("https://api.example.test/api/v1/")
        val saved = GuestSavedRepository.open(disk, GuestStoreScope.Remote(source.baseUrl))
        saved.save(StoreTestFixtures.detail)
        val original = saved.entries.value.single()
        val remote = StoreTestRepository().apply {
            onCatalog = { awaitCancellation() }
            onFilters = { awaitCancellation() }
            onDetail = { error("Local copy must not load detail") }
        }
        val state = AppState(source, backgroundScope, { scope ->
            assertEquals(GuestStoreScope.Remote(source.baseUrl), scope)
            disk
        }, { remote }, FixtureSource { error("REMOTE must never read fixtures") })
        state.load()
        runCurrent()
        val ready = assertIs<BootstrapState.Ready>(state.state.value)
        assertNull(ready.repository)
        assertTrue(ready.catalog.state.value.loading)
        assertTrue(ready.catalog.state.value.metadataLoading)
        assertEquals(listOf(original), ready.saved.entries.value)
        ready.details.showSnapshot(original)
        runCurrent()
        assertEquals(original, assertIs<DetailUiState.Content>(ready.details.state.value).snapshot)
        val day = LocalDate.parse(original.detail.hackathon.starts!!.date)
        assertTrue(SavedCalendar.onDate(ready.saved.entries.value, day).any { it.saved.key == original.key })
        ready.bookmarks.change(original.detail.hackathon, remove = true)
        runCurrent()
        assertTrue(ready.saved.entries.value.isEmpty())
        assertEquals(original.detail, assertIs<DetailUiState.Content>(ready.details.state.value).detail)
        assertTrue(remote.detailCalls.isEmpty())
    }

    @Test fun invalidConfigurationDoesNotOpenStorageOrNetwork() = runTest {
        val state = AppState(null, backgroundScope, { error("No storage") }, { error("No client") })
        state.load()
        assertIs<BootstrapState.ConfigurationUnavailable>(state.state.value)
    }

    @Test fun remoteUnknownVersionIsExplicitFailureWithoutNetworkOrReset() = runTest {
        val disk = TestGuestStorage("{\"version\":999,\"scope\":\"remote-guest@https://api.example.test/api/v1/\",\"entries\":[]}")
        val original = disk.value
        val state = AppState(CatalogSource.Remote("https://api.example.test/api/v1/"), backgroundScope,
            { disk }, { error("Do not create network owner before opening storage") })
        state.load()
        assertIs<BootstrapState.StorageFailed>(state.state.value)
        assertEquals(original, disk.value)
        assertEquals(0, disk.writes)
    }

    private fun TestScope.demo(disk: TestGuestStorage,
        fixtures: FixtureSource = FixtureSource { CanonicalFixtures.files.getValue(it) }) =
        AppState(CatalogSource.BundledDemo, backgroundScope, { disk }, fixtureSource = fixtures)
}
