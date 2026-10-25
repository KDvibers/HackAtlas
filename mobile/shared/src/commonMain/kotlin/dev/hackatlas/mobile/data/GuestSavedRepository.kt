package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.HackathonDetail
import dev.hackatlas.mobile.domain.GuestStoreScope
import dev.hackatlas.mobile.domain.SavedEventKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Platform implementation must replace the previous file atomically, or leave it intact on failure. */
interface GuestSnapshotStorage {
    suspend fun read(): String?
    suspend fun write(contents: String)
}

/** savedAt describes this local copy, never a verification of the official source. */
@Serializable
data class SavedGuestEvent(val detail: HackathonDetail, val savedAt: String) {
    // Computed property: the version-1 serialized entry still contains only detail and savedAt.
    val key: SavedEventKey get() = SavedEventKey(detail.hackathon.id, detail.hackathon.isDemo)
}

@Serializable
private data class GuestDocument(val version: Int, val scope: String, val entries: List<SavedGuestEvent>)

class GuestStorageException(cause: Exception? = null) : Exception("Guest saved data unavailable", cause)

/**
 * Versioned local snapshots, independent of catalog availability and account/session state.
 * Domain serialization is an internal disk format; HTTP DTOs/OpenAPI remain unchanged.
 * A future incompatible disk schema needs an explicit migration, never a silent reset.
 */
class GuestSavedRepository private constructor(
    private val storage: GuestSnapshotStorage,
    val scope: GuestStoreScope,
    initial: List<SavedGuestEvent>,
    private val now: () -> String,
) {
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow(initial.toList())
    val entries = mutableEntries.asStateFlow()

    suspend fun toggle(detail: HackathonDetail) = mutex.withLock {
        guarded { validateDetail(detail, scope) }
        val key = SavedEventKey(detail.hackathon.id, detail.hackathon.isDemo)
        val current = mutableEntries.value
        val next = if (current.any { it.key == key }) current.filterNot { it.key == key }
            else listOf(SavedGuestEvent(detail, now())) + current
        commit(next)
    }

    /** Compatibility for the existing bundled UI. REMOTE callers must supply a classification. */
    suspend fun remove(id: String) {
        require(scope == GuestStoreScope.BundledDemo) { "Remote removal requires SavedEventKey" }
        remove(SavedEventKey(id, isDemo = true))
    }

    suspend fun remove(key: SavedEventKey) = mutex.withLock {
        val current = mutableEntries.value
        if (current.none { it.key == key }) return@withLock
        commit(current.filterNot { it.key == key })
    }

    suspend fun save(detail: HackathonDetail) = mutex.withLock {
        guarded { validateDetail(detail, scope) }
        val key = SavedEventKey(detail.hackathon.id, detail.hackathon.isDemo)
        if (mutableEntries.value.any { it.key == key }) return@withLock
        commit(listOf(SavedGuestEvent(detail, now())) + mutableEntries.value)
    }

    private suspend fun commit(next: List<SavedGuestEvent>) = guarded {
        val document = GuestDocument(VERSION, scope.documentScope, next)
        validate(document, scope)
        // A platform write may finish after UI cancellation. Keep disk and this process's
        // single repository owner consistent across rotation/host disposal.
        withContext(NonCancellable) {
            storage.write(json.encodeToString(document))
            mutableEntries.value = next
        }
    }

    companion object {
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        suspend fun open(
            storage: GuestSnapshotStorage,
            scope: GuestStoreScope = GuestStoreScope.BundledDemo,
            seed: List<HackathonDetail> = emptyList(),
            now: () -> String = { Clock.System.now().toString() },
        ): GuestSavedRepository = guarded {
            require(scope == GuestStoreScope.BundledDemo || seed.isEmpty()) { "Remote guest stores must start empty" }
            val text = storage.read()
            val document = if (text == null) GuestDocument(VERSION, scope.documentScope, seed.map { SavedGuestEvent(it, now()) })
                else json.decodeFromString<GuestDocument>(text)
            validate(document, scope)
            if (text == null) storage.write(json.encodeToString(document))
            GuestSavedRepository(storage, scope, document.entries, now)
        }

        private fun validate(document: GuestDocument, scope: GuestStoreScope) {
            require(document.version == VERSION && document.scope == scope.documentScope)
            require(document.entries.map { it.key }.distinct().size == document.entries.size)
            document.entries.forEach {
                validateDetail(it.detail, scope)
                Instant.parse(it.savedAt)
            }
        }

        private fun validateDetail(detail: HackathonDetail, scope: GuestStoreScope) {
            require(detail.hackathon.id.isNotBlank())
            require(scope != GuestStoreScope.BundledDemo || detail.hackathon.isDemo)
        }

        private suspend inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw GuestStorageException(error)
        }
    }
}
