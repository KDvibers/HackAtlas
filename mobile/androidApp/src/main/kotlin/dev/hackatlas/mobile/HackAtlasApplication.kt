package dev.hackatlas.mobile

import android.app.Application
import dev.hackatlas.mobile.data.AndroidGuestSnapshotStorage
import dev.hackatlas.mobile.data.auth.AndroidSecureTokenStore
import dev.hackatlas.mobile.data.saved.AndroidAccountCacheStorage
import dev.hackatlas.mobile.data.saved.PrivateCacheKind
import dev.hackatlas.mobile.domain.selectCatalogRuntimeConfiguration
import dev.hackatlas.mobile.ui.AppState
import dev.hackatlas.mobile.ui.CatalogRuntime

/** One guest repository owner per process, including Activity recreation during a disk write. */
class HackAtlasApplication : Application() {
    private var runtime: CatalogRuntime? = null

    @Synchronized
    fun guestState(catalogEnabled: Boolean): AppState = (runtime ?: createRuntime(catalogEnabled).also {
        runtime = it
    }).state

    private fun createRuntime(catalogEnabled: Boolean): CatalogRuntime {
        val configuration = selectCatalogRuntimeConfiguration(
            isDebug = BuildConfig.DEBUG,
            sourceMode = BuildConfig.CATALOG_SOURCE,
            baseUrl = BuildConfig.API_BASE_URL,
            accountMode = BuildConfig.ACCOUNT_ACCESS_MODE,
            allowDebugHttp = BuildConfig.ALLOW_DEBUG_HTTP,
        )
        return CatalogRuntime(
            configuration.source,
            { scope -> AndroidGuestSnapshotStorage(this, scope) }, catalogEnabled, BuildConfig.CATALOG_PAGE_SIZE,
            secureStoreFactory = { source -> AndroidSecureTokenStore(this, source) },
            accountCacheFactory = { source -> AndroidAccountCacheStorage(this, source) },
            applicationCacheFactory = { source -> AndroidAccountCacheStorage(this, source, PrivateCacheKind.APPLICATIONS) },
            accessMode = configuration.accessMode,
        )
    }

    fun onForeground() { runtime?.onForeground() }
    fun onBackground() { runtime?.onBackground() }

    override fun onTerminate() {
        runtime?.close()
        super.onTerminate()
    }
}
