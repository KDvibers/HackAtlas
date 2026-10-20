package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.HackathonDetail
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
data class SavedGuestEvent(val detail: HackathonDetail, val savedAt: String)

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
    private val scope: String,
    initial: List<SavedGuestEvent>,
    private val now: () -> String,
) {
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow(initial.toList())
    val entries = mutableEntries.asStateFlow()

    suspend fun toggle(detail: HackathonDetail) = mutex.withLock {
        val id = detail.hackathon.id
        val current = mutableEntries.value
        val next = if (current.any { it.detail.hackathon.id == id }) current.filterNot { it.detail.hackathon.id == id }
            else listOf(SavedGuestEvent(detail, now())) + current
        commit(next)
    }

    suspend fun remove(id: String) = mutex.withLock {
        val current = mutableEntries.value
        if (current.none { it.detail.hackathon.id == id }) return@withLock
        commit(current.filterNot { it.detail.hackathon.id == id })
    }

    suspend fun save(detail: HackathonDetail) = mutex.withLock {
        if (mutableEntries.value.any { it.detail.hackathon.id == detail.hackathon.id }) return@withLock
        commit(listOf(SavedGuestEvent(detail, now())) + mutableEntries.value)
    }

    private suspend fun commit(next: List<SavedGuestEvent>) = guarded {
        val document = GuestDocument(VERSION, scope, next)
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
            scope: String = "demo-guest",
            seed: List<HackathonDetail> = emptyList(),
            now: () -> String = { Clock.System.now().toString() },
        ): GuestSavedRepository = guarded {
            val text = storage.read()
            val document = if (text == null) GuestDocument(VERSION, scope, seed.map { SavedGuestEvent(it, now()) })
                else json.decodeFromString<GuestDocument>(text)
            validate(document, scope)
            if (text == null) storage.write(json.encodeToString(document))
            GuestSavedRepository(storage, scope, document.entries, now)
        }

        private fun validate(document: GuestDocument, scope: String) {
            require(document.version == VERSION && document.scope == scope)
            require(scope == "demo-guest" || scope == "remote-guest")
            require(document.entries.map { it.detail.hackathon.id }.distinct().size == document.entries.size)
            document.entries.forEach {
                require(it.detail.hackathon.id.isNotBlank())
                require(it.detail.hackathon.isDemo == (scope == "demo-guest"))
                Instant.parse(it.savedAt)
            }
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
