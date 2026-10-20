package dev.hackatlas.mobile.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*

/** Prepared on Windows; compilation and filesystem behavior still require a Mac/iOS run. */
@OptIn(ExperimentalForeignApi::class)
class IosGuestSnapshotStorage(demo: Boolean) : GuestSnapshotStorage {
    private val directory = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/GuestSaved"
    private val path = directory + if (demo) "/demo-v1.json" else "/remote-v1.json"

    override suspend fun read(): String? = withContext(Dispatchers.Default) {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return@withContext null
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
            ?: error("Cannot read guest snapshot")
    }

    override suspend fun write(contents: String) = withContext(Dispatchers.Default) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val text = NSString.create(string = contents)
        check(text.writeToFile(path, true, NSUTF8StringEncoding, null))
    }
}
