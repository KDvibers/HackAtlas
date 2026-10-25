package dev.hackatlas.mobile.data

import android.content.Context
import dev.hackatlas.mobile.domain.GuestStoreScope
import java.io.File

class AndroidGuestSnapshotStorage private constructor(context: Context, fileName: String) : GuestSnapshotStorage by AtomicGuestFileStorage(
    File(context.applicationContext.noBackupFilesDir, "guest-saved"),
    fileName,
) {
    constructor(context: Context, scope: GuestStoreScope) : this(context, scope.snapshotFileName())

    /** Existing hosts keep their old files until Task 4 selects an explicit source. */
    constructor(context: Context, demo: Boolean) : this(context, if (demo) "demo-v1.json" else "remote-v1.json")
}
