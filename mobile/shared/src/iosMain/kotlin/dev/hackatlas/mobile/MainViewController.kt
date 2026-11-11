package dev.hackatlas.mobile

import androidx.compose.ui.window.ComposeUIViewController
import dev.hackatlas.mobile.data.IosGuestSnapshotStorage
import dev.hackatlas.mobile.data.auth.IosSecureTokenStore
import dev.hackatlas.mobile.data.saved.IosAccountCacheStorage
import dev.hackatlas.mobile.data.saved.PrivateCacheKind
import dev.hackatlas.mobile.domain.selectCatalogRuntimeConfiguration
import dev.hackatlas.mobile.ui.CatalogRuntime

private var processRuntime: CatalogRuntime? = null

fun MainViewController(isDebug: Boolean, sourceMode: String, apiBaseUrl: String, accountMode: String): platform.UIKit.UIViewController {
    // Called on the main thread by the Swift host. Configuration is fixed for this process.
    val runtime = processRuntime ?: run {
        // iOS REMOTE always requires HTTPS. The host has no debug HTTP opt-in.
        val configuration = selectCatalogRuntimeConfiguration(isDebug, sourceMode, apiBaseUrl, accountMode)
        CatalogRuntime(
            configuration.source,
            { scope -> IosGuestSnapshotStorage(scope) },
            secureStoreFactory = { source -> IosSecureTokenStore(source) },
            accountCacheFactory = { source -> IosAccountCacheStorage(source) },
            applicationCacheFactory = { source -> IosAccountCacheStorage(source, PrivateCacheKind.APPLICATIONS) },
            accessMode = configuration.accessMode,
        ).also { processRuntime = it }
    }
    return ComposeUIViewController { App(runtime.state) }
}

fun closeCatalogRuntime() { processRuntime?.close() }
fun authOnForeground() { processRuntime?.onForeground() }
fun authOnBackground() { processRuntime?.onBackground() }
