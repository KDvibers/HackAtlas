@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.hackatlas.mobile.data.auth

import dev.hackatlas.mobile.data.ContractJson
import dev.hackatlas.mobile.domain.CatalogSource
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import platform.CoreFoundation.*
import platform.Security.*

/** Generic-password Keychain item; no iCloud sync, no migration to another device. */
class IosSecureTokenStore(source: CatalogSource.Remote) : SecureTokenStore {
    override val apiBaseUrl = source.baseUrl

    override suspend fun read(): StoredSession? = io {
        query { query ->
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>(); result.value = null
                val status = SecItemCopyMatching(query, result.ptr)
                if (status == errSecItemNotFound) return@memScoped null
                check(status == errSecSuccess)
                val data = result.value ?: throw SecureStorageException()
                try {
                    val bytes = CFDataGetBytePtr(data.reinterpret())!!.readBytes(CFDataGetLength(data.reinterpret()).toInt())
                    try { ContractJson.decodeFromString<StoredSession>(bytes.decodeToString()).also { it.validate() } }
                    finally { bytes.fill(0) }
                } finally { CFRelease(data) }
            }
        }
    }

    override suspend fun write(session: StoredSession) = io {
        session.validate()
        val bytes = ContractJson.encodeToString(session).encodeToByteArray()
        try {
            bytes.usePinned { pinned ->
                val data = CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()) ?: throw SecureStorageException()
                try {
                    query { query -> dictionary { values ->
                        CFDictionarySetValue(values, kSecValueData, data)
                        CFDictionarySetValue(values, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                        val status = SecItemUpdate(query, values)
                        if (status == errSecItemNotFound) {
                            CFDictionarySetValue(query, kSecValueData, data)
                            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                            check(SecItemAdd(query, null) == errSecSuccess)
                        } else check(status == errSecSuccess)
                    } }
                } finally { CFRelease(data) }
            }
        } finally { bytes.fill(0) }
    }

    override suspend fun clear() = io {
        query { query ->
            val status = SecItemDelete(query)
            check(status == errSecSuccess || status == errSecItemNotFound)
        }
    }

    private fun <T> query(block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, "dev.hackatlas.mobile.auth", kCFStringEncodingUTF8) ?: throw SecureStorageException()
        val account = CFStringCreateWithCString(null, apiBaseUrl, kCFStringEncodingUTF8) ?: run { CFRelease(service); throw SecureStorageException() }
        try {
            return dictionary { query ->
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, service)
                CFDictionarySetValue(query, kSecAttrAccount, account)
                CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
                block(query)
            }
        } finally { CFRelease(service); CFRelease(account) }
    }

    private fun <T> dictionary(block: (CFMutableDictionaryRef) -> T): T {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
            ?: throw SecureStorageException()
        try { return block(query) } finally { CFRelease(query) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.Default) {
        try { block() } catch (_: Exception) { throw SecureStorageException() }
    }
}
