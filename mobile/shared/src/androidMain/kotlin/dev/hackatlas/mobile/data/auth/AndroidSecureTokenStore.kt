package dev.hackatlas.mobile.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.domain.CatalogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext resides on disk; noBackupFilesDir is excluded from Android backups/transfers. */
class AndroidSecureTokenStore internal constructor(
    context: Context, source: CatalogSource.Remote, atomicFile: (File) -> AtomicFile,
) : SecureTokenStore {
    constructor(context: Context, source: CatalogSource.Remote) : this(context, source, ::AtomicFile)
    override val apiBaseUrl = source.baseUrl
    private val name = MessageDigest.getInstance("SHA-256").digest(apiBaseUrl.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val file = File(context.applicationContext.noBackupFilesDir, "auth-sessions/$name.bin")
    private val atomic = atomicFile(file)
    private val alias = "dev.hackatlas.mobile.auth.$name"

    override suspend fun read(): StoredSession? = io {
        val bytes = try { atomic.openRead().use { it.readBytes() } } catch (_: FileNotFoundException) {
            if (present(file) || present(File(file.path + ".bak")) || present(File(file.path + ".new"))) throw SecureStorageException()
            return@io null
        }
        require(bytes.size >= 30 && bytes[0] == 1.toByte() && bytes[1] == 12.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
        cipher.updateAAD(apiBaseUrl.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes.copyOfRange(14, bytes.size))
        try { ContractJson.decodeFromString<StoredSession>(plain.decodeToString()).also { it.validate() } }
        finally { plain.fill(0) }
    }

    override suspend fun write(session: StoredSession) = io {
        session.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(apiBaseUrl.toByteArray(Charsets.UTF_8))
        val plain = ContractJson.encodeToString(session).toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        require(cipher.iv.size == 12)
        val payload = byteArrayOf(1, 12) + cipher.iv + encrypted
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val output = atomic.startWrite()
        try {
            output.write(payload)
            // AtomicFile only logs some sync/close/rename errors. Verify our own sync
            // and read back the committed ciphertext before reporting success.
            output.fd.sync()
            atomic.finishWrite(output)
            check(atomic.openRead().use { it.readBytes() }.contentEquals(payload))
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    override suspend fun clear() = io {
        atomic.delete()
        check(!present(file) && !present(File(file.path + ".bak")) && !present(File(file.path + ".new")))
    }

    // File.exists() conflates ENOENT with EACCES/IO errors and can falsely sign out.
    private fun present(path: File): Boolean = try {
        Os.lstat(path.path)
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try { block() } catch (_: Exception) { throw SecureStorageException() }
        }
    }

    private companion object { val lock = Any() }
}
