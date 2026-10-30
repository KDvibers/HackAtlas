package dev.hackatlas.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.platform.ExternalActions
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailScreen(detail: HackathonDetail, saved: Boolean, format: DisplayFormat, onBack: () -> Unit, onBookmark: () -> Unit, externalActions: ExternalActions, capturedAt: String? = null, bundled: Boolean = true, saving: Boolean = false) {
    val event = detail.hackathon
    val links = remember(detail, bundled) { eventLinkPolicy(detail, bundled) }
    val demoNotice = stringResource(Res.string.demo_share_notice)
    var preview by remember(detail) { mutableStateOf<String?>(null) }
    var actionFailed by remember(detail) { mutableStateOf(false) }
    val onResult: (Boolean) -> Unit = { opened -> if (!opened) actionFailed = true }
    fun openWebsite(url: SafeHttpsUrl) {
        if (links.isDemo) preview = url.value else externalActions.openWebsite(url, onResult)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(AtlasIcon.Back, stringResource(Res.string.back), onBack, AtlasColors.TextPrimary)
                Spacer(Modifier.weight(1f))
                if (event.isDemo) Text(stringResource(Res.string.demo_label), color = AtlasColors.Primary, fontSize = 9.sp, letterSpacing = 1.sp)
                IconAction(AtlasIcon.Bookmark, stringResource(if (saving) Res.string.saving else if (saved) Res.string.unsave_event else Res.string.save_event), onBookmark, if (saved) AtlasColors.Primary else AtlasColors.TextPrimary, enabled = !saving)
                IconAction(AtlasIcon.Share, stringResource(Res.string.share), {
                    links.shareEvent(event.title, demoNotice)?.let { externalActions.shareEvent(it, onResult) }
                }, AtlasColors.TextPrimary, enabled = links.shareUrl != null)
            }
            if (capturedAt != null) {
                Text(stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(capturedAt)), color = AtlasColors.Cyan, fontSize = 11.sp)
                Text(stringResource(Res.string.snapshot_note), color = AtlasColors.TextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 235.dp)) {
                CircuitDecoration(Modifier.matchParentSize(), stronger = true)
                ArtworkView(event.artwork, Modifier.align(Alignment.CenterEnd).width(252.dp).height(250.dp))
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to AtlasColors.Background, .25f to AtlasColors.Background.copy(alpha = .8f), .63f to Color.Transparent)))
                Column(Modifier.padding(top = 30.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TechLabel(event.topics.joinToString(" / ") { it.label })
                    Text(event.title, Modifier.fillMaxWidth(.96f), fontSize = 30.sp, lineHeight = 35.sp, style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(AtlasColors.Background, Offset.Zero, 14f)), fontWeight = FontWeight.Bold)
                    Text(stringResource(Res.string.tech_hero), color = AtlasColors.Cyan, fontSize = 13.sp, lineHeight = 17.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.tech_tomorrow), color = AtlasColors.Primary.copy(alpha = .7f), fontSize = 8.sp, lineHeight = 10.sp, letterSpacing = 1.sp)
                }
            }
            InfoGrid(event, format)
            Spacer(Modifier.height(19.dp))
            SectionTitle(stringResource(Res.string.about_event))
            Spacer(Modifier.height(8.dp))
            Text(detail.description, color = AtlasColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(Res.string.eligibility))
            Spacer(Modifier.height(8.dp))
            Text(detail.eligibility.summary ?: stringResource(Res.string.not_specified), color = AtlasColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
            if (detail.stages.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringResource(Res.string.stages))
                detail.stages.forEach { stage ->
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlineIcon(AtlasIcon.Calendar, Modifier.size(19.dp), AtlasColors.Primary)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stage.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(format.date(stage.whenAt), color = AtlasColors.TextSecondary, fontSize = 12.sp)
                            format.exactTime(stage.whenAt)?.let { Text(it, color = AtlasColors.TextMuted, fontSize = 11.sp) }
                        }
                    }
                }
            }
            detail.submissionRequirements?.let {
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringResource(Res.string.requirements))
                Spacer(Modifier.height(8.dp))
                Text(it, color = AtlasColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
            }
            Spacer(Modifier.height(20.dp))
            SectionTitle(stringResource(Res.string.source))
            Spacer(Modifier.height(8.dp))
            if (bundled) Text(stringResource(Res.string.demo_source), color = AtlasColors.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            else {
                if (event.isDemo) Text(stringResource(Res.string.server_demo_body), color = AtlasColors.Primary, fontSize = 12.sp, lineHeight = 18.sp)
                Text(detail.sourceUrl, color = AtlasColors.Cyan, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Text(stringResource(Res.string.source_verified, event.lastVerifiedAt?.let(format::snapshotTimestamp) ?: stringResource(Res.string.not_verified)), color = AtlasColors.TextMuted, fontSize = 12.sp, lineHeight = 18.sp)
            links.officialUrl?.let { url ->
                TextButton(onClick = { openWebsite(url) }) {
                    Text(stringResource(Res.string.open_official_site), color = AtlasColors.Cyan)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.fillMaxWidth().background(AtlasColors.Background).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val label = when (links.registrationState) {
                RegistrationActionState.AVAILABLE -> Res.string.register_cta
                RegistrationActionState.DEMO_PREVIEW -> Res.string.demo_link_cta
                RegistrationActionState.CLOSED -> Res.string.registration_closed
                RegistrationActionState.CANCELLED -> Res.string.event_cancelled
                RegistrationActionState.ENDED -> Res.string.event_ended
                RegistrationActionState.UNAVAILABLE -> Res.string.registration_unavailable
            }
            NeonButton(stringResource(label), { links.registrationUrl?.let(::openWebsite) }, Modifier.fillMaxWidth(), enabled = links.registrationUrl != null)
            Text(stringResource(if (links.isDemo) Res.string.demo_link_hint else Res.string.registration_external_hint), fontSize = 10.sp, color = AtlasColors.TextMuted)
        }
    }
    if (preview != null || actionFailed) {
        val close = { preview = null; actionFailed = false }
        AlertDialog(onDismissRequest = close,
            title = { Text(stringResource(if (preview != null) Res.string.demo_link_title else Res.string.external_action_error)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (preview != null) Res.string.demo_link_body else Res.string.external_action_error_body), color = AtlasColors.TextSecondary)
                    preview?.let { Text(it, color = AtlasColors.Cyan) }
                }
            },
            containerColor = AtlasColors.SurfaceRaised, titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = close) { Text(stringResource(Res.string.understood), color = AtlasColors.Primary) } })
    }
}

@Composable
private fun InfoGrid(event: Hackathon, format: DisplayFormat) {
    val shape = RoundedCornerShape(13.dp)
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().clip(shape).background(AtlasColors.Surface).border(1.dp, AtlasColors.Border.copy(alpha = .8f), shape)) {
        val cells: List<@Composable (Modifier) -> Unit> = listOf(
            { InfoItem(AtlasIcon.Calendar, stringResource(Res.string.event_dates), format.range(event), modifier = it) },
            { InfoItem(AtlasIcon.Clock, stringResource(Res.string.deadline), format.date(event.registrationDeadline), format.exactTime(event.registrationDeadline), it) },
            { InfoItem(AtlasIcon.Pin, stringResource(Res.string.format), format.location(event), modifier = it) },
            { InfoItem(AtlasIcon.Trophy, stringResource(Res.string.prize_fund), format.prize(event.prize), modifier = it) },
            { InfoItem(AtlasIcon.People, stringResource(Res.string.team_size), "${format.team(event.team)} ${stringResource(Res.string.people_suffix)}", modifier = it) },
            { InfoItem(AtlasIcon.Tag, stringResource(Res.string.topics), event.topics.joinToString(", ") { tag -> tag.label }, modifier = it) },
        )
        if (fontScale > 1.3f) cells.forEach { it(Modifier.fillMaxWidth()) }
        else cells.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row[0](Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(.5.dp).background(AtlasColors.Border.copy(alpha = .55f)))
                row[1](Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AtlasColors.TextPrimary) }
