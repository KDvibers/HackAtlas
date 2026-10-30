package dev.hackatlas.mobile.data.auth

import android.app.Instrumentation
import android.os.Bundle
import android.system.Os
import android.util.AtomicFile
import dev.hackatlas.mobile.domain.CatalogSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Runs in the library test APK, never in the user's Android application. No extra runner dependencies. */
class SecureStoreInstrumentation : Instrumentation() {
    private var case = "all"
    override fun onCreate(arguments: Bundle?) { case = arguments?.getString("case") ?: "all"; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                val a = CatalogSource.Remote("https://auth-store-test.invalid/api/v1/")
                val b = CatalogSource.Remote("https://auth-store-other.invalid/api/v1/")
                val token = "SYNTHETIC-KEYSTORE-SECRET-" + "x".repeat(40)
                val value = StoredSession(token, "2099-01-01T00:00:00Z", "00000000-0000-0000-0000-000000000001")
                val first = AndroidSecureTokenStore(targetContext, a)
                val other = AndroidSecureTokenStore(targetContext, b)
                try {
                    first.clear(); other.clear()
                    check(first.read() == null)
                    first.write(value)
                    check(AndroidSecureTokenStore(targetContext, a).read()?.accessToken == token)
                    check(other.read() == null)
                    fun path(source: CatalogSource.Remote): File {
                        val name = MessageDigest.getInstance("SHA-256").digest(source.baseUrl.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        return File(targetContext.noBackupFilesDir, "auth-sessions/$name.bin")
                    }
                    val file = path(a)
                    check(file.exists() && !file.readBytes().decodeToString().contains(token))
                    val next = StoredSession(token + "new", value.expiresAt, value.userId)
                    first.write(next); check(first.read()?.accessToken == token + "new")
                    other.write(value)
                    path(b).writeBytes(file.readBytes())
                    try { other.read(); error("Copied ciphertext must fail") } catch (_: SecureStorageException) { }
                    file.writeBytes(byteArrayOf(1, 2, 3))
                    try { first.read(); error("Corrupt ciphertext must fail") } catch (_: SecureStorageException) { }
                    first.clear(); check(first.read() == null)
                    first.write(value); check(first.read()?.accessToken == token)
                    suspend fun mustFailStorage(block: suspend () -> Unit) {
                        try { block(); error("Expected storage error") } catch (_: SecureStorageException) { }
                    }
                    if (case == "all" || case == "commit") {
                        val failing = AndroidSecureTokenStore(targetContext, a) { file -> object : AtomicFile(file) {
                            // Models finishWrite returning after a logged rename error, preserving the old base.
                            override fun finishWrite(output: FileOutputStream?) { output?.close() }
                        } }
                        mustFailStorage { failing.write(next) }
                        check(first.read()?.accessToken == token)
                    }
                    if (case == "all" || case.startsWith("permissions")) {
                        val directory = path(a).parentFile!!
                        Os.chmod(directory.path, 0)
                        try {
                            if (case != "permissions-clear") mustFailStorage { first.read() }
                            if (case != "permissions-read") mustFailStorage { first.clear() }
                        } finally { Os.chmod(directory.path, 448) } // 0700, always restore the test-only directory.
                        check(first.read()?.accessToken == token)
                    }
                    result.putString("stream", "PASS native Keystore ($case): encryption, restart, replacement, source isolation, tamper, corruption, clear/reuse, commit/access errors\n")
                } finally { first.clear(); other.clear() }
            }
            finish(-1, result)
        } catch (_: Throwable) {
            result.putString("stream", "FAIL native secure store ($case; details suppressed to avoid credentials)\n")
            finish(0, result)
        }
    }
}
