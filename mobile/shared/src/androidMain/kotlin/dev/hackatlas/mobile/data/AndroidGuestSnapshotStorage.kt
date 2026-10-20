package dev.hackatlas.mobile.data

import android.content.Context
import java.io.File

class AndroidGuestSnapshotStorage(context: Context, demo: Boolean) : GuestSnapshotStorage by AtomicGuestFileStorage(
    File(context.applicationContext.noBackupFilesDir, "guest-saved"),
    if (demo) "demo-v1.json" else "remote-v1.json",
)
