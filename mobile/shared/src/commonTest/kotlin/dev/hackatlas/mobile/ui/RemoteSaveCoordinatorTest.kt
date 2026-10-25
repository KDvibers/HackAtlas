package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSaveCoordinatorTest {
    private val source = GuestStoreScope.Remote("https://api.example.test/api/v1/")
    private val detail = StoreTestFixtures.detail

    @Test fun saveUsesFullDetailAndPreservesDates() = runTest {
        val disk = TestGuestStorage()
        val saved = GuestSavedRepository.open(disk, source, now = { "2026-10-26T10:00:00Z" })
        val repo = StoreTestRepository().apply { onDetail = { detail } }
        RemoteSaveCoordinator(repo, saved).save(detail.hackathon.copy(shortDescription = "Summary only"))
        val snapshot = saved.entries.value.single()
        assertEquals(detail, snapshot.detail)
        assertEquals("2026-10-26T10:00:00Z", snapshot.savedAt)
        assertEquals(listOf(detail.hackathon.id), repo.detailCalls)
        assertEquals(listOf(snapshot), GuestSavedRepository.open(disk, source).entries.value)
    }

    @Test fun failedDetailOrWriteDoesNotMarkSaved() = runTest {
        val disk = TestGuestStorage()
        val saved = GuestSavedRepository.open(disk, source)
        val previous = detail.copy(hackathon = detail.hackathon.copy(id = "previous"))
        saved.save(previous)
        val before = disk.value
        val entries = saved.entries.value
        val repo = StoreTestRepository()
        val coordinator = RemoteSaveCoordinator(repo, saved)
        for (failure in listOf(CatalogError.NotFound, CatalogError.Network, CatalogError.Timeout)) {
            repo.onDetail = { throw CatalogException(failure) }
            assertEquals(failure, assertFailsWith<CatalogException> { coordinator.save(detail.hackathon) }.error)
            assertEquals(before, disk.value)
            assertEquals(entries, saved.entries.value)
        }
        repo.onDetail = { detail }
        disk.failWrite = true
        assertFailsWith<GuestStorageException> { coordinator.save(detail.hackathon) }
        assertEquals(before, disk.value)
        assertEquals(entries, saved.entries.value)
    }

    @Test fun classificationChangeDoesNotSaveWrongEvent() = runTest {
        val disk = TestGuestStorage()
        val saved = GuestSavedRepository.open(disk, source)
        val before = disk.value
        val repo = StoreTestRepository()
        val coordinator = RemoteSaveCoordinator(repo, saved)
        for (event in listOf(detail.hackathon.copy(isDemo = !detail.hackathon.isDemo), detail.hackathon.copy(id = "wrong-id"))) {
            repo.onDetail = { detail.copy(hackathon = event) }
            assertEquals(CatalogError.InvalidResponse, assertFailsWith<CatalogException> { coordinator.save(detail.hackathon) }.error)
            assertTrue(saved.entries.value.isEmpty())
            assertEquals(before, disk.value)
        }
    }

    @Test fun concurrentSaveIsIdempotentAndRemovalNeedsNoNetwork() = runTest {
        val disk = TestGuestStorage()
        val saved = GuestSavedRepository.open(disk, source)
        val repo = StoreTestRepository().apply { onDetail = { delay(100); detail } }
        val coordinator = RemoteSaveCoordinator(repo, saved)
        List(3) { async { coordinator.save(detail.hackathon) } }.awaitAll()
        assertEquals(1, saved.entries.value.size)
        assertEquals(1, repo.detailCalls.size)
        assertEquals(2, disk.writes)
        repo.onDetail = { error("Removal is offline") }
        coordinator.remove(SavedEventKey(detail.hackathon.id, detail.hackathon.isDemo))
        assertTrue(saved.entries.value.isEmpty())
        assertTrue(GuestSavedRepository.open(disk, source).entries.value.isEmpty())
    }

    @Test fun cancelDuringDetailLeavesStorageUnchangedAndOtherRemovalIsIndependent() = runTest {
        val disk = TestGuestStorage()
        val saved = GuestSavedRepository.open(disk, source)
        val other = detail.copy(hackathon = detail.hackathon.copy(id = "other"))
        saved.save(other)
        val repo = StoreTestRepository().apply { onDetail = { awaitCancellation() } }
        val coordinator = RemoteSaveCoordinator(repo, saved)
        val pending = launch { coordinator.save(detail.hackathon) }
        runCurrent()
        coordinator.remove(SavedEventKey("other", other.hackathon.isDemo))
        assertTrue(saved.entries.value.isEmpty())
        val before = disk.value
        pending.cancelAndJoin()
        assertEquals(before, disk.value)
        assertTrue(saved.entries.value.isEmpty())
    }
}
