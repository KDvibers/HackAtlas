package dev.hackatlas.mobile

import android.app.Application
import dev.hackatlas.mobile.data.AndroidGuestSnapshotStorage
import dev.hackatlas.mobile.domain.selectCatalogSource
import dev.hackatlas.mobile.ui.AppState
import dev.hackatlas.mobile.ui.CatalogRuntime

/** One guest repository owner per process, including Activity recreation during a disk write. */
class HackAtlasApplication : Application() {
    private var runtime: CatalogRuntime? = null

    @Synchronized
    fun guestState(catalogEnabled: Boolean): AppState = (runtime ?: CatalogRuntime(
        selectCatalogSource(BuildConfig.DEBUG, BuildConfig.CATALOG_SOURCE, BuildConfig.API_BASE_URL, BuildConfig.ALLOW_DEBUG_HTTP),
        { scope -> AndroidGuestSnapshotStorage(this, scope) }, catalogEnabled,
    ).also { runtime = it }).state

    override fun onTerminate() {
        runtime?.close()
        super.onTerminate()
    }
}
