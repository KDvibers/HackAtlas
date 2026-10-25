package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.GuestSavedRepository
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saves are serialized per identity; a slow detail cannot block unrelated offline removal. */
class RemoteSaveCoordinator(private val repository: CatalogRepository, private val saved: GuestSavedRepository) {
    private val lockMapMutex = Mutex()
    private val locks = mutableMapOf<SavedEventKey, Mutex>()
    private suspend fun lock(key: SavedEventKey): Mutex = lockMapMutex.withLock { locks.getOrPut(key) { Mutex() } }

    suspend fun save(summary: Hackathon) {
        val key = SavedEventKey(summary.id, summary.isDemo)
        lock(key).withLock {
            if (saved.entries.value.any { it.key == key }) return
            val detail = repository.detail(summary.id)
            if (detail.hackathon.id != summary.id || detail.hackathon.isDemo != summary.isDemo)
                throw CatalogException(CatalogError.InvalidResponse)
            saved.save(detail)
        }
    }

    suspend fun remove(key: SavedEventKey) { lock(key).withLock { saved.remove(key) } }

    suspend fun restore(snapshot: SavedGuestEvent) {
        lock(snapshot.key).withLock { saved.save(snapshot.detail) }
    }
}
