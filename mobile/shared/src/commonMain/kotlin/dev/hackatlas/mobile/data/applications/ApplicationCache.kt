package dev.hackatlas.mobile.data.applications

import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.data.saved.AccountCacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ApplicationCacheDocument(
    val version: Int = 1, val apiBaseUrl: String, val userId: String,
    val cachedAt: String? = null, val marks: List<ApplicationMark> = emptyList(),
)

class ApplicationCacheException : Exception("Application cache unavailable")

/** Private membership only; existing saved and guest documents are never opened here. */
class ApplicationCache(val apiBaseUrl: String, private val storage: AccountCacheStorage) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    suspend fun open(userId: String): ApplicationCacheDocument = mutex.withLock { guarded {
        require(USER_ID.matches(userId))
        val text = storage.read() ?: return@guarded ApplicationCacheDocument(apiBaseUrl = apiBaseUrl, userId = userId)
        val doc = json.decodeFromString<ApplicationCacheDocument>(text)
        validate(doc)
        if(doc.userId != userId) {
            withContext(NonCancellable) { storage.clear() }
            ApplicationCacheDocument(apiBaseUrl = apiBaseUrl, userId = userId)
        } else doc
    } }

    suspend fun write(doc: ApplicationCacheDocument, current: () -> Boolean): Boolean = mutex.withLock { guarded {
        validate(doc)
        if(!current()) return@guarded false
        withContext(NonCancellable) { storage.write(json.encodeToString(doc)) }
        current()
    } }

    suspend fun clear() = mutex.withLock { guarded { withContext(NonCancellable) { storage.clear() } } }

    private fun validate(doc: ApplicationCacheDocument) {
        require(doc.version == 1 && doc.apiBaseUrl == apiBaseUrl && USER_ID.matches(doc.userId))
        doc.cachedAt?.let(Instant::parse)
        require(doc.cachedAt != null || doc.marks.isEmpty())
        require(doc.marks.map { it.hackathonId.lowercase() }.distinct().size == doc.marks.size)
        doc.marks.forEach { it.validate() }
    }

    private suspend inline fun <T> guarded(block: () -> T): T = try { block() }
    catch(cancelled: CancellationException) { throw cancelled }
    catch(_: Exception) { throw ApplicationCacheException() }
}
