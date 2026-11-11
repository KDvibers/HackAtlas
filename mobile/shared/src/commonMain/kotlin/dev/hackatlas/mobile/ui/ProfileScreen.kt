package dev.hackatlas.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProfileScreen(
    profile: DemoProfile,
    saved: List<Hackathon>,
    format: DisplayFormat,
    onOpen: (Hackathon) -> Unit,
    onRemove: (Hackathon) -> Unit,
    onAllSaved: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onDemo: () -> Unit,
) {
    ProfileList {
        item(key = "heading") { ProfileHeader(onSettings, onDemo) }
        item(key = "profile") {
            ProfilePanel {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        ProfileIdentity(
                            profile.displayName, initial = profile.displayName.take(1).uppercase(),
                            hooded = profile.avatar == Avatar.HOODED,
                        ) {
                            val details = listOfNotNull(profile.roleLabel, profile.city).filter { it.isNotBlank() }
                            if (details.isNotEmpty()) {
                                Text(
                                    details.joinToString(" · "), color = CatalogueColors.Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    IconAction(AtlasIcon.Edit, stringResource(Res.string.edit_profile), onEdit, tint = CatalogueColors.Lime)
                }
                ProfileStatistics(
                    listOf(
                        profile.appliedCount to stringResource(Res.string.applied_stat),
                        profile.interestsCount to stringResource(Res.string.interests_stat),
                        profile.savedCount to stringResource(Res.string.saved_stat),
                    ),
                )
            }
        }
        item(key = "interests") { ProfileInterests(profile.interests.map { it.label }, onEdit) }
        item(key = "saved-heading") {
            ProfileSectionHeading(
                AtlasIcon.Bookmark, stringResource(Res.string.saved_hackathons), stringResource(Res.string.all), onAllSaved,
            )
        }
        if (saved.isEmpty()) {
            item(key = "saved-empty") {
                Text(
                    stringResource(Res.string.empty_saved_body), color = CatalogueColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(saved.take(3), key = { it.savedKey().listKey() }) { event ->
            HackathonMiniCard(event, format, { onOpen(event) }, { onRemove(event) }, showDemoLabel = false)
        }
    }
}

@Composable
fun GuestProfileScreen(snapshots: List<SavedGuestEvent>, format: DisplayFormat, onOpen: (SavedGuestEvent) -> Unit,
    onRemove: (SavedGuestEvent) -> Unit, onAllSaved: () -> Unit, onSettings: () -> Unit,
    authActions: @Composable () -> Unit = {}) {
    ProfileList {
        item(key = "heading") { ProfileHeader(onSettings) }
        item(key = "guest") {
            ProfilePanel {
                ProfileIdentity(stringResource(Res.string.guest_profile)) {
                    Text(
                        stringResource(Res.string.guest_saved_count, snapshots.size), color = CatalogueColors.Lime,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    stringResource(Res.string.guest_profile_body), color = CatalogueColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item(key = "auth") { authActions() }
        item(key = "saved-heading") {
            ProfileSectionHeading(
                AtlasIcon.Bookmark, stringResource(Res.string.saved_hackathons), stringResource(Res.string.all), onAllSaved,
            )
        }
        if (snapshots.isEmpty()) {
            item(key = "saved-empty") {
                Text(
                    stringResource(Res.string.empty_saved_body), color = CatalogueColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(snapshots.take(3), key = { it.key.listKey() }) { snapshot ->
            HackathonMiniCard(snapshot.detail.hackathon, format, { onOpen(snapshot) }, { onRemove(snapshot) })
        }
    }
}
