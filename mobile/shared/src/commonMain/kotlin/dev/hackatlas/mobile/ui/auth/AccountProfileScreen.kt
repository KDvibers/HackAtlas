package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hackatlas.mobile.data.ProfileDto
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.design.OutlineIcon
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AccountProfileScreen(
    profile: ProfileDto,
    state: AuthUiState,
    snapshots: List<SavedGuestEvent>,
    format: DisplayFormat,
    onOpen: (SavedGuestEvent) -> Unit,
    onRemove: (SavedGuestEvent) -> Unit,
    onAllSaved: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    accountSaved: Boolean = false,
    savedRemoveEnabled: Boolean = true,
    onEdit: (() -> Unit)? = null,
    onVerifyEmail: (() -> Unit)? = null,
    savedStatus: @Composable () -> Unit = {},
) {
    val canReadPrivateStats = state.privateAccess == null || state.canReadPrivate
    ProfileList {
        item(key = "heading") { ProfileHeader(onSettings) }
        item(key = "profile") {
            ProfilePanel {
                ProfileIdentity(
                    profile.displayName, initial = profile.displayName.take(1).uppercase(),
                    hooded = profile.avatarKey == "HOODED",
                ) {
                    Text(profile.email, color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall)
                    val details = listOfNotNull(profile.roleLabel, profile.city, profile.countryCode).filter { it.isNotBlank() }
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "), color = CatalogueColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    stringResource(Res.string.auth_account_stats), color = CatalogueColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                val interests = profile.stats.interestsCount to stringResource(Res.string.interests_stat)
                val stats = if (canReadPrivateStats) {
                    listOf(
                        profile.stats.appliedCount to stringResource(Res.string.applied_stat),
                        interests,
                        profile.stats.savedCount to stringResource(Res.string.saved_stat),
                    )
                } else {
                    listOf(interests)
                }
                ProfileStatistics(stats)
                if (onEdit != null) {
                    TextButton(
                        onClick = onEdit, enabled = state.busy == null && state.error == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Lime),
                    ) {
                        OutlineIcon(AtlasIcon.Edit, Modifier.size(20.dp), LocalContentColor.current)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.edit_profile))
                    }
                }
            }
        }
        item(key = "feedback") { AuthFeedback(state) }
        if (onVerifyEmail != null) {
            item(key = "verification") {
                ProfilePrimaryAction(stringResource(Res.string.verification_title), onVerifyEmail)
            }
        }
        item(key = "interests") { ProfileInterests(profile.interests) }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRefresh, enabled = state.busy == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CatalogueColors.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CatalogueColors.Text),
                ) {
                    Text(stringResource(Res.string.auth_refresh_profile))
                }
                TextButton(
                    onClick = onLogout, enabled = state.busy == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Muted),
                ) {
                    Text(stringResource(Res.string.auth_logout))
                }
            }
        }
        item(key = "saved-heading") {
            ProfileSectionHeading(
                AtlasIcon.Bookmark, stringResource(if (accountSaved) Res.string.account_saved_title else Res.string.auth_local_saved),
                stringResource(Res.string.all), onAllSaved,
            )
            Text(
                stringResource(if (accountSaved) Res.string.account_cache_note else Res.string.auth_local_saved_note),
                color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        item(key = "saved-status") { savedStatus() }
        items(snapshots.take(3), key = { it.key.listKey() }) { snapshot ->
            HackathonMiniCard(
                snapshot.detail.hackathon, format, { onOpen(snapshot) }, { onRemove(snapshot) },
                removeEnabled = savedRemoveEnabled,
            )
        }
    }
}

@Composable
fun GuestAuthActions(store: AuthStore, state: AuthUiState, onLogin: () -> Unit, onRegister: () -> Unit, onLogout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AuthFeedback(state)
        if (state.canAuthenticate) {
            ProfilePrimaryAction(stringResource(Res.string.auth_login), onLogin)
            OutlinedButton(
                onClick = onRegister, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CatalogueColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CatalogueColors.Text),
            ) {
                Text(stringResource(Res.string.auth_register))
            }
        } else if (state.busy == null) {
            Text(
                stringResource(Res.string.auth_session_uncertain), color = CatalogueColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            ProfilePrimaryAction(stringResource(Res.string.auth_retry_session), store::refresh)
            TextButton(
                onClick = onLogout, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Muted),
            ) {
                Text(stringResource(Res.string.auth_logout_device))
            }
        }
    }
}
