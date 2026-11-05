package dev.hackatlas.mobile

import android.app.Application
import dev.hackatlas.mobile.data.AndroidGuestSnapshotStorage
import dev.hackatlas.mobile.data.auth.AndroidSecureTokenStore
import dev.hackatlas.mobile.data.saved.AndroidAccountCacheStorage
import dev.hackatlas.mobile.data.saved.PrivateCacheKind
import dev.hackatlas.mobile.domain.selectCatalogSource
import dev.hackatlas.mobile.ui.AppState
import dev.hackatlas.mobile.ui.CatalogRuntime

/** One guest repository owner per process, including Activity recreation during a disk write. */
class HackAtlasApplication : Application() {
    private var runtime: CatalogRuntime? = null

    @Synchronized
    fun guestState(catalogEnabled: Boolean): AppState = (runtime ?: CatalogRuntime(
        selectCatalogSource(BuildConfig.DEBUG, BuildConfig.CATALOG_SOURCE, BuildConfig.API_BASE_URL, BuildConfig.ALLOW_DEBUG_HTTP),
        { scope -> AndroidGuestSnapshotStorage(this, scope) }, catalogEnabled, BuildConfig.CATALOG_PAGE_SIZE,
        secureStoreFactory = { source -> AndroidSecureTokenStore(this, source) },
        accountCacheFactory = { source -> AndroidAccountCacheStorage(this, source) },
        applicationCacheFactory = { source -> AndroidAccountCacheStorage(this, source, PrivateCacheKind.APPLICATIONS) },
    ).also { runtime = it }).state

    fun onForeground() { runtime?.onForeground() }
    fun onBackground() { runtime?.onBackground() }

    override fun onTerminate() {
        runtime?.close()
        super.onTerminate()
    }
}
