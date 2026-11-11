package dev.hackatlas.mobile.ui.applications

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.auth.AuthError
import dev.hackatlas.mobile.data.auth.PrivateTrust
import dev.hackatlas.mobile.design.AtlasColors
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.CatalogueColors
import dev.hackatlas.mobile.ui.DisplayFormat
import dev.hackatlas.mobile.ui.SectionTitle
import org.jetbrains.compose.resources.stringResource

@Composable
fun ApplicationStatus(id: String, state: ApplicationUiState?, format: DisplayFormat, bundled: Boolean,
    onRefresh: () -> Unit, onRecover: () -> Unit, onChange: (Boolean) -> Unit, onSignIn: (() -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().background(CatalogueColors.Surface,shape)
        .border(1.dp,CatalogueColors.Border,shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.application_title))
        Text(stringResource(Res.string.application_hint),color = CatalogueColors.Muted,fontSize = 12.sp,lineHeight = 17.sp)
        when {
            bundled -> Text(stringResource(Res.string.application_demo_hint),color = CatalogueColors.Muted,fontSize = 12.sp)
            state == null -> Text(stringResource(Res.string.application_unavailable),color = CatalogueColors.Muted,fontSize = 12.sp)
            state.mode == ApplicationMode.CHECKING && state.access?.identity != null -> {
                Text(stringResource(if (state.access.trust == PrivateTrust.PENDING) Res.string.n2_private_pending
                    else Res.string.n2_private_unknown), color = CatalogueColors.Muted, fontSize = 12.sp)
                TextButton(onClick = onRefresh) {
                    Text(stringResource(Res.string.application_refresh), color = CatalogueColors.Lime)
                }
            }
            state.mode == ApplicationMode.CHECKING || state.mode == ApplicationMode.CLEARING ->
                Text(stringResource(Res.string.application_checking),color = CatalogueColors.Muted,fontSize = 12.sp)
            state.mode == ApplicationMode.GUEST -> {
                if(onSignIn != null) ApplicationButton(stringResource(Res.string.application_sign_in),true,onSignIn)
                else Text(stringResource(Res.string.application_unavailable),color = CatalogueColors.Muted,fontSize = 12.sp)
            }
            state.mode == ApplicationMode.BLOCKED -> {
                Text(stringResource(Res.string.application_storage_error),color = AtlasColors.Danger,fontSize = 12.sp,lineHeight = 17.sp)
                ApplicationButton(stringResource(Res.string.account_retry_storage),true,onRecover)
            }
            else -> {
                val mark = state.markFor(id)
                val uncertain = state.uncertainId?.equals(id,ignoreCase = true) == true
                Text(when {
                    uncertain -> stringResource(Res.string.application_uncertain)
                    !state.known -> stringResource(if(state.syncing) Res.string.application_checking else Res.string.application_unknown)
                    mark != null -> stringResource(Res.string.application_confirmed,format.snapshotTimestamp(mark.markedAt))
                    else -> stringResource(Res.string.application_none)
                },color = if(mark != null && !uncertain) CatalogueColors.Turquoise else CatalogueColors.Muted,fontSize = 13.sp,lineHeight = 18.sp)
                if(state.fromCache && state.known) {
                    Text(stringResource(Res.string.application_copy),color = CatalogueColors.Muted,fontSize = 11.sp,lineHeight = 16.sp)
                    state.cachedAt?.let { Text(stringResource(Res.string.snapshot_captured,format.snapshotTimestamp(it)),
                        color = CatalogueColors.Muted,fontSize = 11.sp) }
                }
                if(state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth(),color = CatalogueColors.Lime,trackColor = CatalogueColors.Border)
                if(state.known && !uncertain) {
                    val label = if(state.pendingId?.equals(id,ignoreCase = true) == true)
                        if(mark != null) Res.string.application_removing else Res.string.application_saving
                    else if(mark != null) Res.string.application_remove else Res.string.application_mark
                    ApplicationButton(stringResource(label),state.writable && !state.syncing) { onChange(mark != null) }
                }
                if(!state.writable && !state.syncing && state.error == null)
                    Text(stringResource(Res.string.application_read_only),color = CatalogueColors.Muted,fontSize = 12.sp,lineHeight = 17.sp)
                state.error?.let { Text(applicationError(it),color = AtlasColors.Danger,fontSize = 12.sp,lineHeight = 17.sp) }
                if(!state.writable || state.fromCache || state.error != null)
                    TextButton(onClick = onRefresh,enabled = !state.syncing) {
                        Text(stringResource(Res.string.application_refresh),color = if(state.syncing) CatalogueColors.Muted else CatalogueColors.Lime)
                    }
            }
        }
    }
}

@Composable
private fun ApplicationButton(label: String,enabled: Boolean,onClick: () -> Unit) {
    OutlinedButton(onClick = onClick,enabled = enabled,modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),border = BorderStroke(1.dp,if(enabled) CatalogueColors.Lime else CatalogueColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CatalogueColors.Lime,disabledContentColor = CatalogueColors.Muted)) {
        Text(label,fontSize = 14.sp)
    }
}

@Composable
private fun applicationError(failure: ApplicationFailure): String = when(failure) {
    ApplicationFailure.Storage -> stringResource(Res.string.application_write_error)
    ApplicationFailure.ReadOnly -> stringResource(Res.string.application_read_only)
    ApplicationFailure.Busy -> stringResource(Res.string.application_checking)
    is ApplicationFailure.Auth -> when(val error = failure.error) {
        AuthError.Network -> stringResource(Res.string.auth_network)
        AuthError.Timeout -> stringResource(Res.string.auth_timeout)
        AuthError.Storage -> stringResource(Res.string.auth_storage)
        AuthError.Unauthorized -> stringResource(Res.string.auth_expired)
        AuthError.InvalidResponse, AuthError.UnsupportedServer -> stringResource(Res.string.auth_invalid_response)
        is AuthError.Http -> when(error.status) {
            403 -> stringResource(Res.string.auth_forbidden)
            404 -> stringResource(Res.string.application_not_found)
            429 -> error.retryAfterSeconds?.let { stringResource(Res.string.auth_rate_limit_seconds,it) }
                ?: stringResource(Res.string.auth_rate_limit)
            else -> stringResource(Res.string.application_failed)
        }
    }
}
