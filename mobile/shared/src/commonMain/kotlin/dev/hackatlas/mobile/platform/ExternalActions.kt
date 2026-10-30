package dev.hackatlas.mobile.platform

import androidx.compose.runtime.Composable
import dev.hackatlas.mobile.domain.SafeHttpsUrl
import dev.hackatlas.mobile.domain.SharedEvent

/** Result reports opening/presentation only, never a submission or a completed share. */
interface ExternalActions {
    fun openWebsite(url: SafeHttpsUrl, result: (Boolean) -> Unit)
    fun shareEvent(event: SharedEvent, result: (Boolean) -> Unit)
}

@Composable
expect fun rememberExternalActions(): ExternalActions
