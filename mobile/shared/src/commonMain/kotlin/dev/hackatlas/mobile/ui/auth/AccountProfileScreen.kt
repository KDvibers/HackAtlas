package dev.hackatlas.mobile.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.ProfileDto
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountProfileScreen(profile: ProfileDto, state: AuthUiState, snapshots: List<SavedGuestEvent>, format: DisplayFormat,
    onOpen: (SavedGuestEvent) -> Unit, onRemove: (SavedGuestEvent) -> Unit, onAllSaved: () -> Unit,
    onSettings: () -> Unit, onRefresh: () -> Unit, onLogout: () -> Unit,
    accountSaved: Boolean = false, savedRemoveEnabled: Boolean = true, onEdit:(()->Unit)?=null,
    onVerifyEmail: (() -> Unit)? = null, savedStatus: @Composable () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelHeading(stringResource(Res.string.profile), Modifier.weight(1f), fontSize = 29.sp)
                IconAction(AtlasIcon.Settings, stringResource(Res.string.settings), onSettings)
            }
            TechLabel(stringResource(Res.string.auth_profile_label))
        }
        item {
            val shape = RoundedCornerShape(AtlasDimensions.CardRadius)
            Column(Modifier.fillMaxWidth().background(AtlasColors.Surface, shape).border(1.dp, AtlasColors.Border, shape)
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (profile.avatarKey == "HOODED") Image(painterResource(Res.drawable.demo_avatar), null,
                        Modifier.size(72.dp).clip(CircleShape).border(1.dp, AtlasColors.Primary, CircleShape), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(64.dp).background(AtlasColors.SurfaceRaised, CircleShape)
                        .border(1.dp, AtlasColors.Primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text(profile.displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = AtlasColors.Primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(profile.email, style = MaterialTheme.typography.bodySmall, color = AtlasColors.TextSecondary)
                        val details = listOfNotNull(profile.roleLabel, profile.city, profile.countryCode).filter { it.isNotBlank() }
                        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = AtlasColors.TextSecondary)
                    }
                }
                Text(stringResource(Res.string.auth_account_stats), color = AtlasColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth()) {
                    if (state.privateAccess == null || state.canReadPrivate)
                        ProfileStat(profile.stats.appliedCount, stringResource(Res.string.applied_stat), Modifier.weight(1f))
                    ProfileStat(profile.stats.interestsCount, stringResource(Res.string.interests_stat), Modifier.weight(1f))
                    if (state.privateAccess == null || state.canReadPrivate)
                        ProfileStat(profile.stats.savedCount, stringResource(Res.string.saved_stat), Modifier.weight(1f))
                }
                Text(stringResource(Res.string.profile_motto), color = AtlasColors.Cyan, style = MaterialTheme.typography.labelSmall)
                if(onEdit!=null) TextButton(onClick=onEdit,enabled=state.busy==null && state.error==null,modifier=Modifier.fillMaxWidth()) {
                    OutlineIcon(AtlasIcon.Edit,Modifier.size(20.dp),AtlasColors.Primary)
                    Spacer(Modifier.width(8.dp));Text(stringResource(Res.string.edit_profile),color=AtlasColors.Primary)
                }
            }
        }
        item { AuthFeedback(state) }
        if (onVerifyEmail != null) item {
            NeonButton(stringResource(Res.string.verification_title), onVerifyEmail, Modifier.fillMaxWidth())
        }
        item {
            SectionTitle(stringResource(Res.string.interests))
            Spacer(Modifier.height(12.dp))
            if (profile.interests.isEmpty()) Text(stringResource(Res.string.auth_no_interests), color = AtlasColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.interests.forEach { InterestChip(it) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = state.busy == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(AtlasDimensions.ButtonRadius), border = BorderStroke(1.dp, AtlasColors.Border)) {
                    Text(stringResource(Res.string.auth_refresh_profile))
                }
                TextButton(onClick = onLogout, enabled = state.busy == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.auth_logout), color = AtlasColors.TextSecondary)
                }
            }
        }
        item {
            HorizontalDivider(color = AtlasColors.Border)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if(accountSaved) Res.string.account_saved_title else Res.string.auth_local_saved), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onAllSaved) { Text(stringResource(Res.string.all)) }
            }
            Text(stringResource(if(accountSaved) Res.string.account_cache_note else Res.string.auth_local_saved_note), color = AtlasColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall)
        }
        item { savedStatus() }
        items(snapshots.take(3), key = { it.key.listKey() }) { snapshot ->
            HackathonMiniCard(snapshot.detail.hackathon, format, { onOpen(snapshot) }, { onRemove(snapshot) }, removeEnabled = savedRemoveEnabled)
        }
    }
}

@Composable
fun GuestAuthActions(store: AuthStore, state: AuthUiState, onLogin: () -> Unit, onRegister: () -> Unit, onLogout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AuthFeedback(state)
        if (state.canAuthenticate) {
            NeonButton(stringResource(Res.string.auth_login), onLogin, Modifier.fillMaxWidth())
            OutlinedButton(onClick = onRegister, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(AtlasDimensions.ButtonRadius), border = BorderStroke(1.dp, AtlasColors.Border)) {
                Text(stringResource(Res.string.auth_register))
            }
        } else if (state.busy == null) {
            Text(stringResource(Res.string.auth_session_uncertain), color = AtlasColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium)
            NeonButton(stringResource(Res.string.auth_retry_session), store::refresh, Modifier.fillMaxWidth())
            TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.auth_logout_device), color = AtlasColors.TextSecondary)
            }
        }
    }
}
