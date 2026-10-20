package dev.hackatlas.mobile

import androidx.compose.ui.window.ComposeUIViewController
import dev.hackatlas.mobile.data.IosGuestSnapshotStorage
import dev.hackatlas.mobile.ui.AppState

private val demoGuestState by lazy { AppState(IosGuestSnapshotStorage(demo = true)) }
private val releaseGuestState by lazy { AppState(IosGuestSnapshotStorage(demo = false)) }

fun MainViewController(demoEnabled: Boolean): platform.UIKit.UIViewController {
    val state = if (demoEnabled) demoGuestState else releaseGuestState
    return ComposeUIViewController { App(state, demoEnabled = demoEnabled) }
}
