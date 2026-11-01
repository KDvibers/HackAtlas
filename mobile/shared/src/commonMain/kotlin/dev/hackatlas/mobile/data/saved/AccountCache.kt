package dev.hackatlas.mobile.data.saved

import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.data.auth.USER_ID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface AccountCacheStorage {
    suspend fun read(): String?
    suspend fun write(contents: String)
    suspend fun clear()
}
class AccountCacheException : Exception("Account cache unavailable")

@Serializable
data class AccountCacheDocument(
    val version: Int = 1, val apiBaseUrl: String, val userId: String,
    val cachedAt: String? = null, val entries: List<SavedGuestEvent> = emptyList(),
    val imported: Set<String> = emptySet(),
)

/** Separate private schema; the legacy guest document is never read, rewritten or migrated. */
class AccountCache(val apiBaseUrl: String, private val storage: AccountCacheStorage) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    suspend fun open(userId: String): AccountCacheDocument = mutex.withLock { guarded {
        require(USER_ID.matches(userId))
        val text = storage.read() ?: return@guarded AccountCacheDocument(apiBaseUrl = apiBaseUrl, userId = userId)
        val doc = json.decodeFromString<AccountCacheDocument>(text)
        validate(doc)
        if (doc.userId != userId) {
            withContext(NonCancellable) { storage.clear() }
            AccountCacheDocument(apiBaseUrl = apiBaseUrl, userId = userId)
        } else doc
    } }

    suspend fun write(doc: AccountCacheDocument, current: () -> Boolean): Boolean = mutex.withLock { guarded {
        validate(doc)
        if (!current()) return@guarded false
        // A canceled owner must finish its atomic write before cleanup may delete it.
        withContext(NonCancellable) { storage.write(json.encodeToString(doc)) }
        current()
    } }

    suspend fun clear() = mutex.withLock { guarded { withContext(NonCancellable) { storage.clear() } } }

    private fun validate(doc: AccountCacheDocument) {
        require(doc.version == 1 && doc.apiBaseUrl == apiBaseUrl && USER_ID.matches(doc.userId))
        doc.cachedAt?.let(Instant::parse)
        require(doc.entries.map { it.key }.distinct().size == doc.entries.size)
        doc.entries.forEach { require(USER_ID.matches(it.key.id)); Instant.parse(it.savedAt) }
        doc.imported.forEach { key ->
            require(key.substringBefore(':') in setOf("true", "false") && USER_ID.matches(key.substringAfter(':')))
        }
    }
    private suspend inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw AccountCacheException() }
}
