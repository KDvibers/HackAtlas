package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DetailStoreTest {
    private val firstId = StoreTestFixtures.events[0].id
    private val secondId = StoreTestFixtures.events[1].id

    @Test fun waitsForOpenAndPublishesLoadingThenContent() = runTest {
        val answer = CompletableDeferred<HackathonDetail>()
        val repo = StoreTestRepository().apply { onDetail = { answer.await() } }
        val store = DetailStore(repo, backgroundScope)
        runCurrent()
        assertIs<DetailUiState.Idle>(store.state.value)
        assertTrue(repo.detailCalls.isEmpty())
        store.open(firstId); runCurrent()
        assertEquals(firstId, assertIs<DetailUiState.Loading>(store.state.value).id)
        answer.complete(StoreTestFixtures.detail); runCurrent()
        val content = assertIs<DetailUiState.Content>(store.state.value)
        assertEquals(StoreTestFixtures.detail, content.detail)
        assertNull(content.snapshot)
    }

    @Test fun detail404IsNotDemo() = runTest {
        val repo = StoreTestRepository().apply { onDetail = { throw CatalogException(CatalogError.NotFound) } }
        val store = DetailStore(repo, backgroundScope)
        store.open(firstId); runCurrent()
        val failure = assertIs<DetailUiState.Error>(store.state.value)
        assertEquals(firstId, failure.id)
        assertEquals(CatalogError.NotFound, failure.error)
    }

    @Test fun retryUsesSameIdAndIsSingleFlight() = runTest {
        val retry = CompletableDeferred<HackathonDetail>()
        val repo = StoreTestRepository().apply { onDetail = { throw CatalogException(CatalogError.Network) } }
        val store = DetailStore(repo, backgroundScope)
        store.open(firstId); runCurrent()
        repo.onDetail = { retry.await() }
        store.retry(); store.retry(); runCurrent()
        assertEquals(listOf(firstId, firstId), repo.detailCalls)
        retry.complete(StoreTestFixtures.detail); runCurrent()
        assertIs<DetailUiState.Content>(store.state.value)
        store.retry(); runCurrent()
        assertEquals(2, repo.detailCalls.size)
    }

    @Test fun openingNewDetailRejectsLatePreviousResponse() = runTest {
        val old = CompletableDeferred<HackathonDetail>()
        var oldReturned = false
        val repo = StoreTestRepository().apply {
            onDetail = { id -> if (id == firstId) withContext(NonCancellable) { old.await().also { oldReturned = true } }
                else StoreTestFixtures.detail(id) }
        }
        val store = DetailStore(repo, backgroundScope)
        try {
            store.open(firstId); runCurrent()
            store.open(secondId); runCurrent()
            old.complete(StoreTestFixtures.detail); runCurrent()
            assertTrue(oldReturned)
            assertEquals(secondId, assertIs<DetailUiState.Content>(store.state.value).detail.hackathon.id)
        } finally { old.complete(StoreTestFixtures.detail) }
    }

    @Test fun lateFailureCannotReplaceCurrentDetail() = runTest {
        val old = CompletableDeferred<Unit>()
        val repo = StoreTestRepository().apply {
            onDetail = { id ->
                if (id == firstId) {
                    withContext(NonCancellable) { old.await() }
                    throw CatalogException(CatalogError.Network)
                }
                StoreTestFixtures.detail(id)
            }
        }
        val store = DetailStore(repo, backgroundScope)
        try {
            store.open(firstId); runCurrent()
            store.open(secondId); runCurrent()
            old.complete(Unit); runCurrent()
            assertEquals(secondId, assertIs<DetailUiState.Content>(store.state.value).detail.hackathon.id)
        } finally { old.complete(Unit) }
    }

    @Test fun snapshotNeedsNoNetwork() = runTest {
        val repo = StoreTestRepository().apply { onDetail = { error("Snapshot must not use repository") } }
        val store = DetailStore(repo, backgroundScope)
        val snapshot = SavedGuestEvent(StoreTestFixtures.detail, "2026-10-20T10:30:00Z")
        store.showSnapshot(snapshot); runCurrent()
        val content = assertIs<DetailUiState.Content>(store.state.value)
        assertEquals(snapshot, content.snapshot)
        assertEquals(snapshot.detail, content.detail)
        store.retry(); runCurrent()
        assertTrue(repo.detailCalls.isEmpty())
        assertTrue(repo.catalogCalls.isEmpty())
        assertEquals(0, repo.metadataCalls)
    }

    @Test fun snapshotRejectsLateNetworkResponseEvenForSameId() = runTest {
        val old = CompletableDeferred<HackathonDetail>()
        val repo = StoreTestRepository().apply { onDetail = { withContext(NonCancellable) { old.await() } } }
        val store = DetailStore(repo, backgroundScope)
        val snapshot = SavedGuestEvent(StoreTestFixtures.detail, "2026-10-20T10:30:00Z")
        try {
            store.open(firstId); runCurrent()
            store.showSnapshot(snapshot); runCurrent()
            old.complete(StoreTestFixtures.detail.copy(description = "New server description")); runCurrent()
            assertEquals(snapshot, assertIs<DetailUiState.Content>(store.state.value).snapshot)
            assertEquals(snapshot.detail, assertIs<DetailUiState.Content>(store.state.value).detail)
            assertEquals(listOf(firstId), repo.detailCalls)
        } finally { old.complete(StoreTestFixtures.detail) }
    }

    @Test fun cancelledDetailDoesNotBecomeNetworkError() = runTest {
        val repo = StoreTestRepository().apply { onDetail = { throw CancellationException("cancelled") } }
        val store = DetailStore(repo, backgroundScope)
        store.open(firstId); runCurrent()
        assertIs<DetailUiState.Idle>(store.state.value)
        repo.onDetail = { StoreTestFixtures.detail(it) }
        store.open(secondId); runCurrent()
        assertEquals(secondId, assertIs<DetailUiState.Content>(store.state.value).detail.hackathon.id)
    }
}
