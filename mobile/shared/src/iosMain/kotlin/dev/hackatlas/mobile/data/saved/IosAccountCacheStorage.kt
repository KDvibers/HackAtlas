@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.hackatlas.mobile.data.saved

import dev.hackatlas.mobile.data.storageNamespaceKey
import dev.hackatlas.mobile.domain.CatalogSource
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*

/** Source preparation on Windows; native Foundation/protection behavior needs Mac/iPhone. */
class IosAccountCacheStorage(source: CatalogSource.Remote, kind: PrivateCacheKind = PrivateCacheKind.SAVED) : AccountCacheStorage {
    private val directory = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/" + kind.iosDirectory
    private val path = "$directory/${storageNamespaceKey(source.baseUrl)}.json"
    override suspend fun read(): String? = withContext(Dispatchers.Default) { memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val value = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, error.ptr)
        if (value == null) check(missing(error.value))
        value
    } }
    override suspend fun write(contents: String) = withContext(Dispatchers.Default) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true,
            mapOf(NSFileProtectionKey to NSFileProtectionComplete), null))
        check(NSURL.fileURLWithPath(directory).setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
        check(NSString.create(string = contents).writeToFile(path, true, NSUTF8StringEncoding, null))
    }
    override suspend fun clear() = withContext(Dispatchers.Default) { memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!NSFileManager.defaultManager.removeItemAtPath(path, error.ptr)) check(missing(error.value))
        Unit
    } }
    private fun missing(error: NSError?) = error?.domain == NSCocoaErrorDomain &&
        (error.code == NSFileReadNoSuchFileError || error.code == NSFileNoSuchFileError)
}
