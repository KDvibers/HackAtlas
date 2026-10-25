package dev.hackatlas.mobile.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** API 26+: fsync and atomic replacement errors are propagated rather than only logged. */
internal class AtomicGuestFileStorage(private val directory: File, private val fileName: String) : GuestSnapshotStorage {
    init { require(fileName.matches(Regex("[a-zA-Z0-9_-]+\\.json"))) }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        try { Files.readAllBytes(directory.resolve(fileName).toPath()).toString(Charsets.UTF_8) }
        catch (_: NoSuchFileException) { null }
    }

    override suspend fun write(contents: String) = withContext(Dispatchers.IO) {
        Files.createDirectories(directory.toPath())
        val pending = Files.createTempFile(directory.toPath(), "$fileName-", ".pending")
        try {
            FileChannel.open(pending, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(contents.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(pending, directory.resolve(fileName).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
        Unit
    }
}
