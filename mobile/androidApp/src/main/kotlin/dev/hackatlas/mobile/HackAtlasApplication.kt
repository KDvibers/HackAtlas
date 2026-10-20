package dev.hackatlas.mobile

import android.app.Application
import dev.hackatlas.mobile.data.AndroidGuestSnapshotStorage
import dev.hackatlas.mobile.data.FixtureSource
import dev.hackatlas.mobile.ui.AppState

/** One guest repository owner per process, including Activity recreation during a disk write. */
class HackAtlasApplication : Application() {
    private var guestState: AppState? = null

    @Synchronized
    fun guestState(catalogEnabled: Boolean): AppState = guestState ?: run {
        val storage = AndroidGuestSnapshotStorage(this, demo = BuildConfig.DEBUG)
        val created = if (catalogEnabled) AppState(storage)
            else AppState(storage, FixtureSource { error("Catalog unavailable") })
        guestState = created
        created
    }
}
