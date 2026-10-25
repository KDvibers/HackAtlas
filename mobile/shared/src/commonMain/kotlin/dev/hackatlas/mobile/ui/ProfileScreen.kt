package dev.hackatlas.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(profile: DemoProfile, saved: List<Hackathon>, format: DisplayFormat, onOpen: (Hackathon) -> Unit, onRemove: (Hackathon) -> Unit, onAllSaved: () -> Unit, onSettings: () -> Unit, onEdit: () -> Unit, onDemo: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelHeading(stringResource(Res.string.profile), Modifier.weight(1f), fontSize = 29.sp)
                IconAction(AtlasIcon.Settings, stringResource(Res.string.settings), onSettings)
            }
            Text(stringResource(Res.string.profile_subtitle), color = AtlasColors.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        item { ProfileCard(profile, onEdit, onDemo) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlineIcon(AtlasIcon.Sparkles, Modifier.size(19.dp), AtlasColors.Primary)
                Spacer(Modifier.width(8.dp))
                SectionTitle(stringResource(Res.string.interests))
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.edit), Modifier.heightIn(min = 48.dp).wrapContentHeight().clickable(role = Role.Button, onClick = onEdit).padding(vertical = 15.dp), fontSize = 12.sp, lineHeight = 16.sp, color = AtlasColors.Primary)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.interests.forEach { InterestChip(it.label) }
            }
        }
        item {
            HorizontalDivider(color = AtlasColors.Border.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlineIcon(AtlasIcon.Bookmark, Modifier.size(19.dp), AtlasColors.Primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.saved_hackathons), Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAllSaved), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.all), color = AtlasColors.Primary, fontSize = 12.sp)
                    OutlineIcon(AtlasIcon.ChevronRight, Modifier.size(16.dp), AtlasColors.Primary)
                }
            }
        }
        if (saved.isEmpty()) item { Text(stringResource(Res.string.empty_saved_body), color = AtlasColors.TextSecondary, fontSize = 13.sp) }
        items(saved.take(3), key = { it.id }) { HackathonMiniCard(it, format, { onOpen(it) }, { onRemove(it) }) }
    }
}

@Composable
private fun ProfileCard(profile: DemoProfile, onEdit: () -> Unit, onDemo: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brush.verticalGradient(listOf(AtlasColors.SurfaceRaised, AtlasColors.Surface))).border(1.dp, AtlasColors.Border, shape).padding(15.dp)) {
        Box {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(80.dp).background(Brush.radialGradient(listOf(AtlasColors.Primary.copy(alpha = .22f), AtlasColors.Primary.copy(alpha = 0f)))), contentAlignment = Alignment.Center) {
                    Image(painterResource(Res.drawable.demo_avatar), contentDescription = null, Modifier.size(75.dp).clip(CircleShape).border(1.dp, AtlasColors.Primary.copy(alpha = .8f), CircleShape), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.padding(end = 35.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(Res.string.demo_label), color = AtlasColors.Primary, fontSize = 8.sp, lineHeight = 12.sp)
                    }
                    Text(listOfNotNull(profile.roleLabel, profile.city).joinToString(" · "), color = AtlasColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth()) {
                        ProfileStat(profile.appliedCount, stringResource(Res.string.applied_stat), Modifier.weight(1f))
                        ProfileStat(profile.interestsCount, stringResource(Res.string.interests_stat), Modifier.weight(1f))
                        ProfileStat(profile.savedCount, stringResource(Res.string.saved_stat), Modifier.weight(1f))
                    }
                }
            }
            IconAction(AtlasIcon.Edit, stringResource(Res.string.edit_profile), onEdit, modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-9).dp))
        }
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineIcon(AtlasIcon.Sparkles, Modifier.size(17.dp), AtlasColors.Primary)
            Text(stringResource(Res.string.profile_motto), color = AtlasColors.Cyan.copy(alpha = .8f), fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = .5.sp)
        }
    }
}

@Composable
private fun ProfileStat(value: Int, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value.toString(), color = AtlasColors.Primary, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = AtlasColors.TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
    }
}

@Composable
fun SavedScreen(snapshots: List<SavedGuestEvent>, format: DisplayFormat, onOpen: (Hackathon) -> Unit, onRemove: (Hackathon) -> Unit, onCatalog: () -> Unit, onDemo: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PixelHeading(stringResource(Res.string.saved), fontSize = 23.sp)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.saved_local_subtitle), color = AtlasColors.Cyan, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Text(stringResource(Res.string.snapshot_note), color = AtlasColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            DemoBadge(onDemo)
        }
        if (snapshots.isEmpty()) item { StatePanel(stringResource(Res.string.empty_saved), stringResource(Res.string.empty_saved_body), stringResource(Res.string.go_catalog), onCatalog) }
        items(snapshots, key = { it.detail.hackathon.id }) { snapshot ->
            val event = snapshot.detail.hackathon
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                HackathonMiniCard(event, format, { onOpen(event) }, { onRemove(event) })
                Text(stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(snapshot.savedAt)), color = AtlasColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}
