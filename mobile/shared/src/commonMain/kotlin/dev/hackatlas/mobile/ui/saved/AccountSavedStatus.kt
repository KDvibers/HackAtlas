package dev.hackatlas.mobile.ui.saved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.CatalogueColors
import dev.hackatlas.mobile.ui.DisplayFormat
import dev.hackatlas.mobile.ui.ProfileHeader
import dev.hackatlas.mobile.ui.ProfileIdentity
import dev.hackatlas.mobile.ui.ProfileList
import dev.hackatlas.mobile.ui.ProfilePanel
import dev.hackatlas.mobile.ui.ProfilePrimaryAction
import dev.hackatlas.mobile.ui.StatePanel
import dev.hackatlas.mobile.ui.auth.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AccountSavedStatus(store: AccountSavedStore, state: AccountSavedUiState, guest: List<SavedGuestEvent>,
    format: DisplayFormat, onImport: () -> Unit) {
    if(state.mode == SavedMode.GUEST) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when(state.mode) {
            SavedMode.CHECKING, SavedMode.CLEARING -> {
                if (state.mode == SavedMode.CHECKING && state.access?.identity != null) {
                    Text(stringResource(if (state.access.trust == PrivateTrust.PENDING) Res.string.n2_private_pending
                        else Res.string.n2_private_unknown), color = AtlasColors.TextSecondary)
                    TextButton(onClick = store::refresh) { Text(stringResource(Res.string.account_refresh)) }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = AtlasColors.Primary, trackColor = AtlasColors.Border)
                    Text(stringResource(if(state.mode == SavedMode.CLEARING) Res.string.account_clearing else Res.string.account_checking),
                        color = AtlasColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            SavedMode.BLOCKED -> StatePanel(stringResource(Res.string.account_saved_title), stringResource(Res.string.account_storage_error),
                stringResource(Res.string.account_retry_storage), store::retryCleanup)
            SavedMode.ACCOUNT -> {
                TechLabel(stringResource(Res.string.account_saved_title))
                state.cachedAt?.let { Text(stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(it)),
                    color = AtlasColors.TextMuted, style = MaterialTheme.typography.bodySmall) }
                    ?: Text(stringResource(Res.string.account_cache_missing), color = AtlasColors.TextSecondary)
                if(!state.writable) Text(stringResource(Res.string.account_read_only), color = AtlasColors.Cyan,
                    style = MaterialTheme.typography.bodySmall)
                if(state.syncing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = AtlasColors.Primary, trackColor = AtlasColors.Border)
                    Text(stringResource(Res.string.account_syncing), color = AtlasColors.TextSecondary)
                }
                state.error?.let { error ->
                    Text(stringResource(when(error) {
                        SavedFailure.Storage -> Res.string.account_write_error
                        SavedFailure.ReadOnly -> Res.string.account_read_only
                        SavedFailure.Busy -> Res.string.account_busy
                        else -> Res.string.account_sync_error
                    }), color = AtlasColors.Danger, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = store::refresh, enabled = !state.syncing, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.account_refresh), color = AtlasColors.Cyan)
                }
                val done = guest.count { journalKey(it.key) in state.imported }
                if(guest.isNotEmpty()) {
                    if(done == guest.size) Text(stringResource(Res.string.account_copy_done), color = AtlasColors.Success,
                        style = MaterialTheme.typography.bodySmall)
                    else {
                        if(done > 0) Text(stringResource(Res.string.account_copy_progress, done, guest.size), color = AtlasColors.Cyan)
                        NeonButton(stringResource(Res.string.account_copy_guest, guest.size - done), onImport,
                            Modifier.fillMaxWidth(), enabled = state.writable && !state.syncing)
                    }
                }
            }
            SavedMode.GUEST -> Unit
        }
    }
}

@Composable
fun UnverifiedAccountProfile(auth: AuthStore, state: AuthUiState, onLogout: () -> Unit, savedContent: @Composable () -> Unit) {
    ProfileList {
        item(key = "heading") { ProfileHeader() }
        item(key = "session") {
            ProfilePanel {
                ProfileIdentity(stringResource(Res.string.account_unverified_profile))
                Text(
                    stringResource(if (state.privateAccess == null) Res.string.account_unverified_body else Res.string.n2_private_unknown),
                    color = CatalogueColors.Muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthFeedback(state)
                ProfilePrimaryAction(stringResource(Res.string.auth_retry_session), auth::refresh, enabled = state.busy == null)
                TextButton(
                    onClick = onLogout, enabled = state.busy == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Muted),
                ) { Text(stringResource(Res.string.auth_logout_device)) }
            }
        }
        item(key = "saved-status") { savedContent() }
    }
}
