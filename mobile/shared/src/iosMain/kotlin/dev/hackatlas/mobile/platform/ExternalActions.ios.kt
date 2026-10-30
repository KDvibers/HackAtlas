package dev.hackatlas.mobile.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import dev.hackatlas.mobile.domain.SafeHttpsUrl
import dev.hackatlas.mobile.domain.SharedEvent
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

@Composable
actual fun rememberExternalActions(): ExternalActions {
    val controller = LocalUIViewController.current
    return remember(controller) { IosExternalActions(controller) }
}

@OptIn(ExperimentalForeignApi::class)
private class IosExternalActions(private val host: UIViewController) : ExternalActions {
    private var sharing = false

    override fun openWebsite(url: SafeHttpsUrl, result: (Boolean) -> Unit) {
        val destination = NSURL.URLWithString(url.value)
        if (destination == null || host.view.window == null) {
            result(false)
            return
        }
        UIApplication.sharedApplication.openURL(destination, options = emptyMap<Any?, Any>(), completionHandler = result)
    }

    override fun shareEvent(event: SharedEvent, result: (Boolean) -> Unit) {
        if (sharing || host.view.window == null || host.presentedViewController != null) {
            result(false)
            return
        }
        val sheet = UIActivityViewController(activityItems = listOf(event.text), applicationActivities = null)
        // iPad requires an anchor even when the iPhone presentation is a bottom sheet.
        sheet.popoverPresentationController?.let { popover ->
            popover.sourceView = host.view
            popover.sourceRect = host.view.bounds.useContents {
                CGRectMake(origin.x + size.width / 2, origin.y + size.height / 2, 1.0, 1.0)
            }
        }
        sharing = true
        host.presentViewController(sheet, animated = true, completion = {
            // Once presented, UIKit owns dismissal (including an outside iPad tap).
            // Do not latch availability to a "share completed" callback.
            sharing = false
            result(true)
        })
    }
}
