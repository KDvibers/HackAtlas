package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.data.saved.*
import dev.hackatlas.mobile.ui.StoreTestFixtures
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountCacheTest {
    private val base = "https://saved.example.org/api/v1/"
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private val at = "2026-11-01T12:00:00Z"
    private class Disk : AccountCacheStorage {
        var value: String? = null; var failWrite = false; var failClear = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read() = value
        override suspend fun write(contents: String) { gate?.await(); if(failWrite) error("disk"); value = contents }
        override suspend fun clear() { if(failClear) error("denied"); value = null }
    }
    @Test fun matchingOwnerCanReopenSnapshotsAndJournal() = runTest {
        val disk = Disk(); val cache = AccountCache(base, disk)
        val snapshot = SavedGuestEvent(StoreTestFixtures.detail, at)
        val doc = cache.open(owner).copy(entries = listOf(snapshot), cachedAt = at, imported = setOf("${snapshot.key.isDemo}:${snapshot.key.id}"))
        assertTrue(cache.write(doc) { true })
        assertEquals(doc, AccountCache(base, disk).open(owner))
    }
    @Test fun differentOwnerClearsOldDataAndNeverReturnsIt() = runTest {
        val disk = Disk(); val cache = AccountCache(base, disk)
        cache.write(cache.open(owner).copy(cachedAt = at)) { true }
        val doc = cache.open(second)
        assertEquals(second, doc.userId); assertNull(doc.cachedAt); assertNull(disk.value)
    }
    @Test fun corruptOrForeignSourceIsExplicitFailureWithoutReset() = runTest {
        val disk = Disk().apply { value = "broken" }
        assertFailsWith<AccountCacheException> { AccountCache(base, disk).open(owner) }
        assertEquals("broken", disk.value)
        val cache = AccountCache(base, Disk())
        assertFailsWith<AccountCacheException> { cache.write(cache.open(owner).copy(apiBaseUrl = "https://other.example.org/api/v1/")) { true } }
    }
    @Test fun failedWriteOrClearNeverReportsSuccess() = runTest {
        val disk = Disk(); val cache = AccountCache(base, disk); val doc = cache.open(owner)
        cache.write(doc) { true }; val before = disk.value
        disk.failWrite = true
        assertFailsWith<AccountCacheException> { cache.write(doc.copy(cachedAt = at)) { true } }
        assertEquals(before, disk.value)
        disk.failClear = true
        assertFailsWith<AccountCacheException> { cache.clear() }
        assertEquals(before, disk.value)
    }
    @Test fun logoutClearWaitsForAlreadyStartedWriteAndDeletesIt() = runTest {
        val disk = Disk(); val cache = AccountCache(base, disk); val doc = cache.open(owner)
        val gate = CompletableDeferred<Unit>(); disk.gate = gate
        var current = true
        val writer = async { cache.write(doc.copy(cachedAt = at)) { current } }
        runCurrent(); current = false
        val clear = async { cache.clear() }; runCurrent()
        assertFalse(clear.isCompleted)
        gate.complete(Unit); writer.await(); clear.await()
        assertNull(disk.value)
        assertFalse(cache.write(doc) { false }); assertNull(disk.value)
    }
}
