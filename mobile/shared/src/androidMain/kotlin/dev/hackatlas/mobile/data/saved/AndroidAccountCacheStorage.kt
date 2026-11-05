package dev.hackatlas.mobile.data.saved

import android.content.Context
import dev.hackatlas.mobile.data.AtomicGuestFileStorage
import dev.hackatlas.mobile.data.storageNamespaceKey
import dev.hackatlas.mobile.domain.CatalogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** App-private, no-backup file; contains event snapshots/membership, never credentials. */
class AndroidAccountCacheStorage(context: Context, source: CatalogSource.Remote,
    kind: PrivateCacheKind = PrivateCacheKind.SAVED) : AccountCacheStorage {
    private val directory = File(context.applicationContext.noBackupFilesDir, kind.androidDirectory)
    private val name = storageNamespaceKey(source.baseUrl) + ".json"
    private val file = directory.resolve(name).toPath()
    private val atomic = AtomicGuestFileStorage(directory, name)
    override suspend fun read() = atomic.read()
    override suspend fun write(contents: String) = atomic.write(contents)
    override suspend fun clear() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file)
        // Atomic staging files can survive a process kill. Remove only this API's own prefix.
        try {
            Files.newDirectoryStream(directory.toPath()).use { paths ->
                paths.filter { it.fileName.toString().startsWith("$name-") && it.fileName.toString().endsWith(".pending") }
                    .forEach { Files.deleteIfExists(it) }
            }
        } catch (_: NoSuchFileException) { /* no directory means no private cache */ }
        Unit
    }
}
