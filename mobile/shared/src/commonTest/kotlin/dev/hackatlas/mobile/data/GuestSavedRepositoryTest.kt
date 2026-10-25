package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.HackathonDetail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class GuestSavedRepositoryTest {
    private suspend fun event(): HackathonDetail =
        DemoRepository.load(FixtureSource { CanonicalFixtures.files.getValue(it) })
            .detail("39287368-5259-5235-a1c4-25b4e1e8421e")!!

    @Test fun savedDetailsSurviveNewRepositoryWithoutCatalog() = runTest {
        val disk = TestGuestStorage()
        val detail = event()
        val first = GuestSavedRepository.open(disk, now = { "2026-10-20T12:00:00Z" })
        first.toggle(detail)
        val restored = GuestSavedRepository.open(disk)
        assertEquals(detail, restored.entries.value.single().detail)
        assertEquals("2026-10-20T12:00:00Z", restored.entries.value.single().savedAt)
        assertEquals(detail.hackathon.lastVerifiedAt, restored.entries.value.single().detail.hackathon.lastVerifiedAt)
    }

    @Test fun removingLastBookmarkRemainsEmptyAfterRestartAndDoesNotReseed() = runTest {
        val disk = TestGuestStorage()
        val detail = event()
        val first = GuestSavedRepository.open(disk, seed = listOf(detail))
        first.remove(detail.hackathon.id)
        assertTrue(GuestSavedRepository.open(disk, seed = listOf(detail)).entries.value.isEmpty())
    }

    @Test fun failedWriteKeepsPreviousMemoryAndDisk() = runTest {
        val disk = TestGuestStorage()
        val repository = GuestSavedRepository.open(disk, seed = listOf(event()))
        val before = disk.value
        disk.failWrite = true
        assertFailsWith<GuestStorageException> { repository.remove(event().hackathon.id) }
        assertEquals(1, repository.entries.value.size)
        assertEquals(before, disk.value)
    }

    @Test fun retryAfterWriteFailureWorks() = runTest {
        val disk = TestGuestStorage()
        val repository = GuestSavedRepository.open(disk)
        val detail = event()
        disk.failWrite = true
        assertFailsWith<GuestStorageException> { repository.toggle(detail) }
        disk.failWrite = false
        repository.toggle(detail)
        assertEquals(detail, GuestSavedRepository.open(disk).entries.value.single().detail)
    }

    @Test fun concurrentTogglesDoNotLoseUpdates() = runTest {
        val repository = GuestSavedRepository.open(TestGuestStorage())
        val detail = event()
        List(20) { async { repository.toggle(detail) } }.awaitAll()
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test fun corruptedFileIsNotReplacedBySeed() = runTest {
        val disk = TestGuestStorage("{broken")
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, seed = listOf(event())) }
        assertEquals("{broken", disk.value)
        assertEquals(0, disk.writes)
    }

    @Test fun unsupportedVersionIsPreserved() = runTest {
        val disk = TestGuestStorage("""{"version":99,"scope":"demo-guest","entries":[]}""")
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk) }
        assertEquals(0, disk.writes)
    }

    @Test fun scopeMismatchCannotLoadOrOverwriteAnotherStore() = runTest {
        val disk = TestGuestStorage()
        GuestSavedRepository.open(disk, seed = listOf(event()))
        val before = disk.value
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = "remote-guest") }
        assertEquals(before, disk.value)
    }

    @Test fun demoCannotBeSavedIntoRemoteScope() = runTest {
        val repository = GuestSavedRepository.open(TestGuestStorage(), scope = "remote-guest")
        assertFailsWith<GuestStorageException> { repository.toggle(event()) }
        assertTrue(repository.entries.value.isEmpty())
    }

    @Test fun newSaveComesFirstAndRemovalOfMissingIdDoesNotWrite() = runTest {
        val detail = event()
        val other = detail.copy(hackathon = detail.hackathon.copy(id = "another-event", title = "Other"))
        val disk = TestGuestStorage()
        val repository = GuestSavedRepository.open(disk)
        repository.toggle(detail)
        repository.toggle(other)
        assertEquals(listOf("another-event", detail.hackathon.id), repository.entries.value.map { it.detail.hackathon.id })
        val writes = disk.writes
        repository.remove("missing")
        assertEquals(writes, disk.writes)
    }

    @Test fun cancellationDuringDiskCommitCannotLeaveMemoryBehindDisk() = runTest {
        val disk = TestGuestStorage()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pauseWrite = false
        val storage = object : GuestSnapshotStorage {
            override suspend fun read() = disk.read()
            override suspend fun write(contents: String) {
                if (!pauseWrite) disk.write(contents)
                else withContext(Dispatchers.Default) {
                    // Models an uninterruptible platform write which finishes after UI disposal.
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        disk.write(contents)
                    }
                }
            }
        }
        val repository = GuestSavedRepository.open(storage)
        val detail = event()
        pauseWrite = true
        val job = launch { repository.toggle(detail) }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(GuestSavedRepository.open(disk).entries.value, repository.entries.value)
        assertEquals(1, repository.entries.value.size)
    }

    @Test fun repeatedSaveAndRemoveIntentIsIdempotent() = runTest {
        val disk = TestGuestStorage()
        val repository = GuestSavedRepository.open(disk)
        val detail = event()
        List(5) { async { repository.save(detail) } }.awaitAll()
        assertEquals(1, repository.entries.value.size)
        assertEquals(2, disk.writes) // Initial empty document plus one committed save.
        List(5) { async { repository.remove(detail.hackathon.id) } }.awaitAll()
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(3, disk.writes)
    }
}

internal class TestGuestStorage(var value: String? = null) : GuestSnapshotStorage {
    var failWrite = false
    var writes = 0
    override suspend fun read(): String? = value
    override suspend fun write(contents: String) {
        if (failWrite) error("Simulated full disk")
        value = contents
        writes++
    }
}
