package dev.hackatlas.mobile.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.hackatlas.mobile.domain.SafeHttpsUrl
import dev.hackatlas.mobile.domain.SharedEvent

@Composable
actual fun rememberExternalActions(): ExternalActions {
    val context = LocalContext.current
    return remember(context) { AndroidExternalActions(context) }
}

private class AndroidExternalActions(private val context: Context) : ExternalActions {
    override fun openWebsite(url: SafeHttpsUrl, result: (Boolean) -> Unit) = launch(
        Intent(Intent.ACTION_VIEW, Uri.parse(url.value)).addCategory(Intent.CATEGORY_BROWSABLE), result,
    )

    override fun shareEvent(event: SharedEvent, result: (Boolean) -> Unit) = launch(
        Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, event.subject)
            putExtra(Intent.EXTRA_TEXT, event.text)
        }, null), result,
    )

    private fun launch(intent: Intent, result: (Boolean) -> Unit) {
        var host = context
        while (host is ContextWrapper && host !is Activity && host.baseContext !== host) host = host.baseContext
        if (host is Activity && (host.isFinishing || host.isDestroyed)) {
            result(false)
            return
        }
        if (host !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
        result(opened)
    }
}
