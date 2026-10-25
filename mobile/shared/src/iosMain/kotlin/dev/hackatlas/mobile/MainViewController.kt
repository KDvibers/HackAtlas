package dev.hackatlas.mobile

import androidx.compose.ui.window.ComposeUIViewController
import dev.hackatlas.mobile.data.IosGuestSnapshotStorage
import dev.hackatlas.mobile.domain.selectCatalogSource
import dev.hackatlas.mobile.ui.CatalogRuntime

private var processRuntime: CatalogRuntime? = null

fun MainViewController(isDebug: Boolean, sourceMode: String, apiBaseUrl: String): platform.UIKit.UIViewController {
    // Called on the main thread by the Swift host. Configuration is fixed for this process.
    // No ATS exceptions: iOS REMOTE requires HTTPS, including Debug until a dev host is agreed.
    val runtime = processRuntime ?: CatalogRuntime(selectCatalogSource(isDebug, sourceMode, apiBaseUrl),
        { scope -> IosGuestSnapshotStorage(scope) }).also { processRuntime = it }
    return ComposeUIViewController { App(runtime.state) }
}

fun closeCatalogRuntime() { processRuntime?.close() }
