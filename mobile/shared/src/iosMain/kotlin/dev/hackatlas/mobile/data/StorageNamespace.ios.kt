package dev.hackatlas.mobile.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/** Source reviewed on Windows; Native compilation and common digest vectors require a Mac. */
@OptIn(ExperimentalForeignApi::class)
actual fun storageNamespaceKey(canonicalBaseUrl: String): String {
    val input = canonicalBaseUrl.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { data ->
        digest.usePinned { output ->
            checkNotNull(CC_SHA256(
                if (input.isEmpty()) null else data.addressOf(0),
                input.size.toUInt(),
                output.addressOf(0).reinterpret<UByteVar>(),
            ))
        }
    }
    return digest.storageHex()
}
