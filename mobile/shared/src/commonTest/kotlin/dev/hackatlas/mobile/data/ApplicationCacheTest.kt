package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.data.applications.*
import dev.hackatlas.mobile.data.auth.ApplicationMark
import dev.hackatlas.mobile.data.saved.AccountCacheStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ApplicationCacheTest {
    private val base = "https://marks.example.org/api/v1/"
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val at = "2026-11-05T10:00:00Z"
    private val mark = ApplicationMark("39287368-5259-5235-a1c4-25b4e1e8421e", "APPLIED", true, at)
    private class Disk : AccountCacheStorage {
        var text: String? = null;var failClear = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read() = text
        override suspend fun write(contents: String) { gate?.await();text = contents }
        override suspend fun clear() { if(failClear) error("denied");text = null }
    }

    @Test fun knownEmptyAndPopulatedMembershipSurviveReopen() = runTest {
        val disk = Disk();val cache = ApplicationCache(base, disk);val empty = cache.open(owner)
        assertNull(empty.cachedAt)
        assertTrue(cache.write(empty.copy(cachedAt = at)) { true })
        assertEquals(at, ApplicationCache(base, disk).open(owner).cachedAt)
        assertTrue(cache.write(empty.copy(cachedAt = at, marks = listOf(mark))) { true })
        assertEquals(listOf(mark), ApplicationCache(base, disk).open(owner).marks)
    }

    @Test fun anotherOwnerClearsPrivateDataBeforeOpening() = runTest {
        val disk = Disk();val cache = ApplicationCache(base, disk)
        cache.write(cache.open(owner).copy(cachedAt = at, marks = listOf(mark))) { true }
        val next = cache.open(other)
        assertEquals(other, next.userId);assertTrue(next.marks.isEmpty());assertNull(next.cachedAt);assertNull(disk.text)
    }

    @Test fun malformedWrongSourceAndInvalidMarksFailClosed() = runTest {
        val disk = Disk();val cache = ApplicationCache(base, disk)
        disk.text = "{}";assertFailsWith<ApplicationCacheException> { cache.open(owner) }
        val empty = ApplicationCacheDocument(apiBaseUrl = base, userId = owner)
        for(doc in listOf(empty.copy(apiBaseUrl = "https://other.example.org/api/v1/"), empty.copy(version = 2),
            empty.copy(marks = listOf(mark)), empty.copy(cachedAt = "invalid"),
            empty.copy(cachedAt = at, marks = listOf(mark.copy(selfReported = false))),
            empty.copy(cachedAt = at, marks = listOf(mark, mark.copy(hackathonId = mark.hackathonId.uppercase()))))) {
            assertFailsWith<ApplicationCacheException> { cache.write(doc) { true } }
        }
    }

    @Test fun failedOwnerCleanupDoesNotExposeOldMarks() = runTest {
        val disk = Disk();val cache = ApplicationCache(base, disk)
        cache.write(cache.open(owner).copy(cachedAt = at, marks = listOf(mark))) { true }
        disk.failClear = true;assertFailsWith<ApplicationCacheException> { cache.open(other) }
    }

    @Test fun obsoleteWriterCannotOverwriteDisk() = runTest {
        val disk = Disk();val cache = ApplicationCache(base, disk);val doc = cache.open(owner)
        assertFalse(cache.write(doc.copy(cachedAt = at, marks = listOf(mark))) { false });assertNull(disk.text)
    }

    @Test fun canceledAtomicWriteCompletesBeforeCleanup() = runTest {
        val disk = Disk();val cache = ApplicationCache(base, disk);val doc = cache.open(owner)
        disk.gate = CompletableDeferred()
        val writer = launch { cache.write(doc.copy(cachedAt = at, marks = listOf(mark))) { true } }
        yield();writer.cancel()
        val cleanup = launch { cache.clear() };yield()
        assertFalse(cleanup.isCompleted)
        disk.gate!!.complete(Unit);writer.join();cleanup.join();assertNull(disk.text)
    }
}
