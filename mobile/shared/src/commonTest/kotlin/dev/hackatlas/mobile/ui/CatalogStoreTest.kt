package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogStoreTest {
    private val events = StoreTestFixtures.events

    @Test fun initialPageSizeRejectsValuesOutsideContractBeforeStartingRequests() = runTest {
        val repo = StoreTestRepository()
        for (size in listOf(0, 51, 100)) {
            assertFailsWith<IllegalArgumentException> {
                CatalogStore(repo, backgroundScope, initialPageSize = size)
            }
        }
        runCurrent()
        assertTrue(repo.catalogCalls.isEmpty())
        for (size in listOf(1, 50)) {
            CatalogStore(repo, backgroundScope, initialPageSize = size)
        }
        runCurrent()
        assertEquals(listOf(1, 50), repo.catalogCalls.map { it.size })
    }

    @Test fun initialLoadingPreservesServerOrderAndClock() = runTest {
        val answer = CompletableDeferred<CatalogPage>()
        val repo = StoreTestRepository().apply { onCatalog = { answer.await() } }
        val store = CatalogStore(repo, backgroundScope)
        assertTrue(store.state.value.loading)
        assertFalse(store.state.value.isEmpty)
        runCurrent()
        answer.complete(StoreTestFixtures.page(items = listOf(events[2], events[0])))
        runCurrent()
        assertEquals(listOf(events[2], events[0]), store.state.value.items)
        assertEquals(StoreTestFixtures.AS_OF, store.state.value.asOf)
        assertEquals(StoreTestFixtures.metadata, store.state.value.metadata)
        assertFalse(store.state.value.loading)
        assertNull(store.state.value.error)
    }

    @Test fun successfulEmptyPageIsDifferentFromLoadingOrFailure() = runTest {
        val repo = StoreTestRepository().apply { onCatalog = { StoreTestFixtures.page(it, emptyList()) } }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        assertTrue(store.state.value.isEmpty)
        assertNull(store.state.value.nextPage)
        assertNull(store.state.value.error)
    }

    @Test fun searchUsesThreeHundredMillisecondDebounce() = runTest {
        val repo = StoreTestRepository()
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.setQuery(CatalogQuery(query = "A"))
        runCurrent()
        advanceTimeBy(200)
        store.setQuery(CatalogQuery(query = "AI"))
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertEquals(1, repo.catalogCalls.size)
        assertEquals("AI", store.state.value.query.query)
        assertTrue(store.state.value.loading)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("", "AI"), repo.catalogCalls.map { it.query })
        store.setQuery(CatalogQuery(query = "AI"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, repo.catalogCalls.size)
    }

    @Test fun filtersSortAndResetApplyImmediatelyFromPageZero() = runTest {
        val repo = StoreTestRepository()
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.setQuery(CatalogQuery(query = "pending"))
        runCurrent()
        store.setQuery(CatalogQuery(query = "pending", format = EventFormat.ONLINE, countryCode = "KZ", page = 9))
        runCurrent()
        assertEquals(2, repo.catalogCalls.size)
        assertEquals(0, repo.catalogCalls.last().page)
        assertEquals("KZ", repo.catalogCalls.last().countryCode)
        store.setQuery(store.state.value.query.copy(sort = CatalogSort.NEWEST, page = 7))
        runCurrent()
        assertEquals(CatalogSort.NEWEST, repo.catalogCalls.last().sort)
        assertEquals(0, repo.catalogCalls.last().page)
        store.setQuery(CatalogQuery())
        runCurrent()
        assertEquals(CatalogQuery(), repo.catalogCalls.last())
        assertEquals(4, repo.catalogCalls.size)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(4, repo.catalogCalls.size)
        // Deleting only the search text is also an immediate reset.
        store.setQuery(CatalogQuery(query = "x"))
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        store.setQuery(CatalogQuery())
        runCurrent()
        assertEquals(CatalogQuery(), repo.catalogCalls.last())
        assertEquals(6, repo.catalogCalls.size)
    }

    @Test fun lateSearchCannotReplaceNewResults() = runTest {
        val old = CompletableDeferred<CatalogPage>()
        var oldReturned = false
        val repo = StoreTestRepository().apply {
            onCatalog = { query ->
                if (query.query == "A") withContext(NonCancellable) { old.await().also { oldReturned = true } }
                else StoreTestFixtures.page(query, listOf(events[1]))
            }
        }
        val store = CatalogStore(repo, backgroundScope)
        try {
            runCurrent()
            store.setQuery(CatalogQuery(query = "A"))
            runCurrent(); advanceTimeBy(300); runCurrent()
            store.setQuery(CatalogQuery(query = "B"))
            runCurrent(); advanceTimeBy(300); runCurrent()
            old.complete(StoreTestFixtures.page(items = listOf(events[0])))
            runCurrent()
            assertTrue(oldReturned)
            assertEquals("B", store.state.value.query.query)
            assertEquals(listOf(events[1]), store.state.value.items)
            assertNull(store.state.value.error)
        } finally { old.complete(StoreTestFixtures.page()) }
    }

    @Test fun lateFailureCannotReplaceNewSuccessOrDebounceState() = runTest {
        val old = CompletableDeferred<Unit>()
        val repo = StoreTestRepository().apply {
            onCatalog = { query ->
                if (query.query == "A") {
                    withContext(NonCancellable) { old.await() }
                    throw CatalogException(CatalogError.Network)
                }
                StoreTestFixtures.page(query)
            }
        }
        val store = CatalogStore(repo, backgroundScope)
        try {
            runCurrent()
            store.setQuery(CatalogQuery(query = "A"))
            runCurrent(); advanceTimeBy(300); runCurrent()
            store.setQuery(CatalogQuery(query = "B"))
            runCurrent()
            old.complete(Unit)
            runCurrent()
            assertTrue(store.state.value.loading)
            assertNull(store.state.value.error)
            advanceTimeBy(300); runCurrent()
            assertEquals("B", store.state.value.query.query)
            assertNull(store.state.value.error)
        } finally { old.complete(Unit) }
    }

    @Test fun pagingIsBoundToQuery() = runTest {
        val oldPage = CompletableDeferred<CatalogPage>()
        var oldReturned = false
        val repo = StoreTestRepository().apply {
            onCatalog = { query ->
                when {
                    query.page == 1 -> withContext(NonCancellable) { oldPage.await().also { oldReturned = true } }
                    query.countryCode == "KZ" -> StoreTestFixtures.page(query, listOf(events[2]))
                    else -> StoreTestFixtures.page(query, listOf(events[0]), hasNext = true)
                }
            }
        }
        val store = CatalogStore(repo, backgroundScope)
        try {
            runCurrent()
            store.loadNext(); runCurrent()
            store.setQuery(CatalogQuery(countryCode = "KZ")); runCurrent()
            oldPage.complete(StoreTestFixtures.page(CatalogQuery(page = 1), listOf(events[1])))
            runCurrent()
            assertTrue(oldReturned)
            assertEquals("KZ", store.state.value.query.countryCode)
            assertEquals(listOf(events[2]), store.state.value.items)
            assertNull(store.state.value.nextPage)
            assertFalse(store.state.value.loadingMore)
        } finally { oldPage.complete(StoreTestFixtures.page(CatalogQuery(page = 1))) }
    }

    @Test fun loadNextIsSingleFlightAndStopsAtLastPage() = runTest {
        val second = CompletableDeferred<CatalogPage>()
        val repo = StoreTestRepository().apply {
            onCatalog = { query -> if (query.page == 0) StoreTestFixtures.page(query, hasNext = true) else second.await() }
        }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.loadNext(); store.loadNext(); runCurrent()
        assertEquals(listOf(0, 1), repo.catalogCalls.map { it.page })
        assertTrue(store.state.value.loadingMore)
        second.complete(StoreTestFixtures.page(CatalogQuery(page = 1), listOf(events[2])))
        runCurrent()
        repeat(3) { store.loadNext() }; runCurrent()
        assertEquals(2, repo.catalogCalls.size)
        assertNull(store.state.value.nextPage)
    }

    @Test fun appendDeduplicatesIdsAndPreservesServerOrderWithoutLocalFiltering() = runTest {
        val repo = StoreTestRepository().apply {
            onCatalog = { query ->
                if (query.page == 0) StoreTestFixtures.page(query, listOf(events[2], events[0], events[2]), true)
                else StoreTestFixtures.page(query, listOf(events[0], events[1], events[1]), asOf = "2026-10-12T10:01:00Z")
            }
        }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.setQuery(CatalogQuery(format = EventFormat.OFFLINE)); runCurrent()
        store.loadNext(); runCurrent()
        assertEquals(listOf(events[2], events[0], events[1]), store.state.value.items)
        assertEquals("2026-10-12T10:01:00Z", store.state.value.asOf)
    }

    @Test fun emptyPageWithHasNextIsInvalidAndCannotLoop() = runTest {
        val repo = StoreTestRepository().apply {
            onCatalog = { query -> StoreTestFixtures.page(query,
                if (query.page == 0) listOf(events[0]) else emptyList(), hasNext = true) }
        }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.loadNext(); runCurrent()
        assertEquals(CatalogError.InvalidResponse, store.state.value.appendError)
        assertEquals(listOf(events[0]), store.state.value.items)
        repeat(3) { store.loadNext() }; runCurrent()
        assertEquals(2, repo.catalogCalls.size)
        assertFalse(store.state.value.loadingMore)
    }

    @Test fun mismatchedPageCannotBeAppended() = runTest {
        val repo = StoreTestRepository().apply {
            onCatalog = { query ->
                if (query.page == 0) StoreTestFixtures.page(query, listOf(events[0]), true)
                else StoreTestFixtures.page(items = listOf(events[1]))
            }
        }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.loadNext(); runCurrent()
        assertEquals(CatalogError.InvalidResponse, store.state.value.appendError)
        assertEquals(listOf(events[0]), store.state.value.items)
    }

    @Test fun metadataFailureDoesNotHideCatalog() = runTest {
        val repo = StoreTestRepository().apply { onFilters = { throw CatalogException(CatalogError.Network) } }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        assertEquals(CatalogError.Network, store.state.value.metadataError)
        assertNull(store.state.value.error)
        assertEquals(events.take(2), store.state.value.items)
        store.setQuery(CatalogQuery(format = EventFormat.ONLINE)); runCurrent()
        assertEquals(EventFormat.ONLINE, repo.catalogCalls.last().format)
        assertEquals(events.take(2), store.state.value.items)
        repo.onFilters = { StoreTestFixtures.metadata }
        store.retryMetadata(); store.retryMetadata(); runCurrent()
        assertEquals(2, repo.metadataCalls)
        assertEquals(2, repo.catalogCalls.size)
        assertEquals(StoreTestFixtures.metadata, store.state.value.metadata)
        assertNull(store.state.value.metadataError)
    }

    @Test fun loadingMetadataDoesNotBlockCatalog() = runTest {
        val metadata = CompletableDeferred<FilterMetadata>()
        val repo = StoreTestRepository().apply { onFilters = { metadata.await() } }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        assertEquals(events.take(2), store.state.value.items)
        assertFalse(store.state.value.loading)
        assertTrue(store.state.value.metadataLoading)
        metadata.complete(StoreTestFixtures.metadata); runCurrent()
        assertFalse(store.state.value.metadataLoading)
    }

    @Test fun initialFailureIsNotEmptySuccess() = runTest {
        val repo = StoreTestRepository().apply { onCatalog = { throw CatalogException(CatalogError.Timeout) } }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        assertEquals(CatalogError.Timeout, store.state.value.error)
        assertNull(store.state.value.asOf)
        assertFalse(store.state.value.isEmpty)
        assertFalse(store.state.value.loading)
        repo.onCatalog = { StoreTestFixtures.page(it) }
        store.retry(); store.retry(); runCurrent()
        assertEquals(listOf(0, 0), repo.catalogCalls.map { it.page })
        assertEquals(events.take(2), store.state.value.items)
        assertNull(store.state.value.error)
        assertEquals(1, repo.metadataCalls)
    }

    @Test fun appendFailureKeepsItemsAndRetriesSamePage() = runTest {
        var failAppend = true
        val repo = StoreTestRepository().apply {
            onCatalog = { query ->
                if (query.page > 0 && failAppend) throw CatalogException(CatalogError.Network)
                StoreTestFixtures.page(query, listOf(events[query.page]), query.page == 0)
            }
        }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        store.loadNext(); runCurrent()
        assertEquals(listOf(events[0]), store.state.value.items)
        assertEquals(CatalogError.Network, store.state.value.appendError)
        assertNull(store.state.value.error)
        assertEquals(StoreTestFixtures.AS_OF, store.state.value.asOf)
        store.loadNext(); runCurrent()
        assertEquals(2, repo.catalogCalls.size)
        failAppend = false
        store.retry(); store.retry(); runCurrent()
        assertEquals(listOf(0, 1, 1), repo.catalogCalls.map { it.page })
        assertEquals(listOf(events[0], events[1]), store.state.value.items)
        assertNull(store.state.value.appendError)
    }

    @Test fun cancellationIsNotNetworkFailureAndCanBeRetried() = runTest {
        val repo = StoreTestRepository().apply { onCatalog = { throw CancellationException("cancelled") } }
        val store = CatalogStore(repo, backgroundScope)
        runCurrent()
        assertNull(store.state.value.error)
        assertFalse(store.state.value.isEmpty)
        assertFalse(store.state.value.loading)
        repo.onCatalog = { StoreTestFixtures.page(it) }
        store.retry(); runCurrent()
        assertEquals(events.take(2), store.state.value.items)
    }

    @Test fun ownerCancellationCancelsBothRequests() = runTest {
        var catalogCancelled = false
        var metadataCancelled = false
        val repo = StoreTestRepository().apply {
            onCatalog = { try { awaitCancellation() } finally { catalogCancelled = true } }
            onFilters = { try { awaitCancellation() } finally { metadataCancelled = true } }
        }
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val store = CatalogStore(repo, owner)
        runCurrent()
        owner.cancel(); runCurrent()
        assertTrue(catalogCancelled)
        assertTrue(metadataCancelled)
        assertNull(store.state.value.error)
        assertNull(store.state.value.metadataError)
        store.retry(); store.retryMetadata(); runCurrent()
        assertEquals(1, repo.catalogCalls.size)
        assertEquals(1, repo.metadataCalls)
    }
}
