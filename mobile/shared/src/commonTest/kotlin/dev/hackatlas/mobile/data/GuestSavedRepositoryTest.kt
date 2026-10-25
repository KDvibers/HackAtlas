package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.HackathonDetail
import dev.hackatlas.mobile.domain.GuestStoreScope
import dev.hackatlas.mobile.domain.SavedEventKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GuestSavedRepositoryTest {
    @Test fun legacyBundledDemoStillOpens() = runTest {
        val disk = TestGuestStorage(LEGACY_DEMO_GUEST_V1)
        val repository = GuestSavedRepository.open(disk, seed = listOf(event()), now = { error("Must not reseed") })
        assertEquals(event(), repository.entries.value.single().detail)
        assertEquals("2026-10-20T12:00:00Z", repository.entries.value.single().savedAt)
        assertEquals(SavedEventKey(event().hackathon.id, true), repository.entries.value.single().key)
        assertEquals(GuestStoreScope.BundledDemo, repository.scope)
        assertEquals("demo-v1.json", repository.scope.snapshotFileName())
        assertEquals(LEGACY_DEMO_GUEST_V1, disk.value)
        assertEquals(0, disk.writes)
    }

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
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = remote()) }
        assertEquals(before, disk.value)
    }

    @Test fun serverDemoAndRealAreSeparate() = runTest {
        val disk = TestGuestStorage()
        val demo = event()
        val real = demo.copy(hackathon = demo.hackathon.copy(isDemo = false))
        val repository = GuestSavedRepository.open(disk, scope = remote(), now = { "2026-10-26T10:00:00Z" })
        repository.save(demo)
        repository.save(real)
        repository.save(demo)
        assertEquals(listOf(SavedEventKey(demo.hackathon.id, false), SavedEventKey(demo.hackathon.id, true)),
            repository.entries.value.map { it.key })
        assertEquals(3, disk.writes) // Empty remote document and one save per classification.
        val restored = GuestSavedRepository.open(disk, scope = remote())
        assertEquals(repository.entries.value, restored.entries.value)
        restored.remove(SavedEventKey(demo.hackathon.id, true))
        assertEquals(real, restored.entries.value.single().detail)
        assertEquals(listOf(false), GuestSavedRepository.open(disk, scope = remote()).entries.value.map { it.key.isDemo })
        val writes = disk.writes
        restored.remove(SavedEventKey(demo.hackathon.id, true))
        assertEquals(writes, disk.writes)
    }

    @Test fun remoteToggleOnlyChangesTheExactClassification() = runTest {
        val disk = TestGuestStorage()
        val demo = event()
        val real = demo.copy(hackathon = demo.hackathon.copy(isDemo = false))
        val repository = GuestSavedRepository.open(disk, scope = remote())
        repository.save(demo)
        repository.save(real)
        repository.toggle(demo)
        assertEquals(real, repository.entries.value.single().detail)
        repository.toggle(demo)
        assertEquals(setOf(true, false), repository.entries.value.map { it.key.isDemo }.toSet())
        repository.toggle(real)
        assertEquals(demo, repository.entries.value.single().detail)
    }

    @Test fun bundledDemoRejectsRealDataWithoutRemovingSameId() = runTest {
        val disk = TestGuestStorage()
        val demo = event()
        val real = demo.copy(hackathon = demo.hackathon.copy(isDemo = false))
        val repository = GuestSavedRepository.open(disk, seed = listOf(demo))
        val before = disk.value
        assertFailsWith<GuestStorageException> { repository.save(real) }
        assertFailsWith<GuestStorageException> { repository.toggle(real) }
        assertEquals(listOf(demo), repository.entries.value.map { it.detail })
        assertEquals(before, disk.value)
        assertEquals(1, disk.writes)
    }

    @Test fun remoteSeedIsRejectedWithoutCreatingOrReplacingData() = runTest {
        val disk = TestGuestStorage()
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = remote(), seed = listOf(event())) }
        assertNull(disk.value)
        assertEquals(0, disk.writes)
        GuestSavedRepository.open(disk, scope = remote())
        val before = disk.value
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = remote(), seed = listOf(event())) }
        assertEquals(before, disk.value)
        assertEquals(1, disk.writes)
    }

    @Test fun remoteRemovalRequiresAnExplicitKey() = runTest {
        val disk = TestGuestStorage()
        val repository = GuestSavedRepository.open(disk, scope = remote())
        val demo = event()
        repository.save(demo)
        repository.save(demo.copy(hackathon = demo.hackathon.copy(isDemo = false)))
        val before = disk.value
        assertFailsWith<IllegalArgumentException> { repository.remove(demo.hackathon.id) }
        assertEquals(2, repository.entries.value.size)
        assertEquals(before, disk.value)
    }

    @Test fun duplicateRemoteKeysAreRejectedWithoutRewriting() = runTest {
        val disk = TestGuestStorage()
        GuestSavedRepository.open(disk, scope = remote()).save(event())
        val document = Json.parseToJsonElement(disk.value!!).jsonObject
        val entry = document.getValue("entries").jsonArray.single()
        disk.value = JsonObject(document + ("entries" to JsonArray(listOf(entry, entry)))).toString()
        val before = disk.value
        val writes = disk.writes
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = remote()) }
        assertEquals(before, disk.value)
        assertEquals(writes, disk.writes)
    }

    @Test fun legacyUnscopedRemoteDocumentIsNotSilentlyImported() = runTest {
        val before = """{"version":1,"scope":"remote-guest","entries":[]}"""
        val disk = TestGuestStorage(before)
        assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = remote()) }
        assertEquals(before, disk.value)
        assertEquals(0, disk.writes)
    }

    @Test fun persistedVersionOneFieldsStayCompatible() = runTest {
        val disk = TestGuestStorage(LEGACY_DEMO_GUEST_V1)
        val repository = GuestSavedRepository.open(disk)
        val detail = event()
        repository.save(detail.copy(hackathon = detail.hackathon.copy(id = "second-demo")))
        val document = Json.parseToJsonElement(disk.value!!).jsonObject
        assertEquals(setOf("version", "scope", "entries"), document.keys)
        assertEquals(1, document.getValue("version").jsonPrimitive.int)
        assertEquals("demo-guest", document.getValue("scope").jsonPrimitive.content)
        assertTrue(document.getValue("entries").jsonArray.all { it.jsonObject.keys == setOf("detail", "savedAt") })
        assertEquals("2026-10-20T12:00:00Z", repository.entries.value.last().savedAt)
    }

    @Test fun failedRemoteWritePreservesBothClassifications() = runTest {
        val disk = TestGuestStorage()
        val repository = GuestSavedRepository.open(disk, scope = remote())
        val demo = event()
        repository.save(demo)
        repository.save(demo.copy(hackathon = demo.hackathon.copy(isDemo = false)))
        val before = disk.value
        val entries = repository.entries.value
        disk.failWrite = true
        assertFailsWith<GuestStorageException> { repository.remove(SavedEventKey(demo.hackathon.id, false)) }
        assertEquals(before, disk.value)
        assertEquals(entries, repository.entries.value)
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
    private fun remote() = GuestStoreScope.Remote("https://api.example.test/api/v1/")
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
