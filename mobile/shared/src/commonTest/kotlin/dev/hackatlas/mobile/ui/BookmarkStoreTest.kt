package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkStoreTest {
    private val detail = StoreTestFixtures.detail
    private val source = GuestStoreScope.Remote("https://api.example.test/api/v1/")

    @Test fun recreatedObserverKeepsPendingAndUnacknowledgedFailure() = runTest {
        for (error in listOf(BookmarkError.Catalog(CatalogError.Timeout),
            BookmarkError.Catalog(CatalogError.NotFound), BookmarkError.Storage)) {
            val disk = TestGuestStorage()
            val saved = GuestSavedRepository.open(disk, source)
            val original = disk.value
            val response = CompletableDeferred<Unit>()
            val repo = StoreTestRepository().apply {
                onDetail = {
                    response.await()
                    if (error is BookmarkError.Catalog) throw CatalogException(error.error)
                    detail
                }
            }
            disk.failWrite = error == BookmarkError.Storage
            val store = BookmarkStore(RemoteSaveCoordinator(repo, saved), backgroundScope)
            val oldUi = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.collect() }
            store.change(detail.hackathon, remove = false)
            runCurrent()
            val key = detail.hackathon.savedKey()
            assertEquals(setOf(key), store.state.value.pendingKeys)
            oldUi.cancelAndJoin()
            val observed = mutableListOf<BookmarkUiState>()
            val newUi = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.state.toList(observed) }
            assertEquals(setOf(key), observed.last().pendingKeys)
            store.change(detail.hackathon, remove = false)
            runCurrent()
            assertEquals(1, repo.detailCalls.size)
            response.complete(Unit)
            runCurrent()
            assertTrue(observed.last().pendingKeys.isEmpty())
            assertEquals(error, observed.last().failures.single().error)
            assertEquals(original, disk.value)
            assertTrue(saved.entries.value.isEmpty())
            newUi.cancelAndJoin()
            // Another recreation after the failure must replay it until explicit dismissal.
            val failure = store.state.first().failures.single()
            store.acknowledgeFailure(failure.id)
            runCurrent()
            assertTrue(store.state.value.failures.isEmpty())
        }
    }

    @Test fun completedSaveAndOfflineRemovalAndRestoreUpdateSharedState() = runTest {
        val saved = GuestSavedRepository.open(TestGuestStorage(), source)
        val response = CompletableDeferred<Unit>()
        val repo = StoreTestRepository().apply { onDetail = { response.await(); detail } }
        val store = BookmarkStore(RemoteSaveCoordinator(repo, saved), backgroundScope)
        store.change(detail.hackathon, remove = false)
        runCurrent()
        assertEquals(setOf(detail.hackathon.savedKey()), store.state.first().pendingKeys)
        response.complete(Unit)
        runCurrent()
        val snapshot = saved.entries.value.single()
        assertTrue(store.state.value.pendingKeys.isEmpty())
        assertTrue(store.state.value.failures.isEmpty())
        repo.onDetail = { error("Local copies must not need HTTP") }
        store.change(detail.hackathon, remove = true)
        runCurrent()
        assertTrue(saved.entries.value.isEmpty())
        store.change(detail.hackathon, remove = false, snapshot = snapshot)
        runCurrent()
        assertEquals(detail, saved.entries.value.single().detail)
        assertTrue(store.state.value.failures.isEmpty())
        assertEquals(1, repo.detailCalls.size)
    }

    @Test fun acknowledgingOneFailureDoesNotDropAnotherOperationFailure() = runTest {
        val saved = GuestSavedRepository.open(TestGuestStorage(), source)
        val repo = StoreTestRepository().apply { onDetail = { throw CatalogException(CatalogError.Network) } }
        val store = BookmarkStore(RemoteSaveCoordinator(repo, saved), backgroundScope)
        store.change(detail.hackathon, remove = false)
        store.change(detail.hackathon.copy(id = "other"), remove = false)
        runCurrent()
        val failures = store.state.value.failures
        assertEquals(2, failures.size)
        store.acknowledgeFailure(failures.first().id)
        runCurrent()
        assertEquals(listOf(failures.last()), store.state.value.failures)
        store.acknowledgeFailure(failures.first().id)
        runCurrent()
        assertEquals(listOf(failures.last()), store.state.value.failures)
    }
}
