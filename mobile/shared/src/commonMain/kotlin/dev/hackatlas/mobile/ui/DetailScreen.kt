package dev.hackatlas.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.platform.ExternalActions
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.applications.ApplicationStatus
import dev.hackatlas.mobile.ui.applications.ApplicationUiState
import org.jetbrains.compose.resources.stringResource

@Composable
fun DetailScreen(
    detail: HackathonDetail,
    saved: Boolean,
    format: DisplayFormat,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    externalActions: ExternalActions,
    capturedAt: String? = null,
    bundled: Boolean = true,
    saving: Boolean = false,
    applications: ApplicationUiState? = null,
    onApplicationsRefresh: () -> Unit = {},
    onApplicationsRecover: () -> Unit = {},
    onApplicationChange: (Boolean) -> Unit = {},
    onApplicationSignIn: (() -> Unit)? = null,
) {
    val event = detail.hackathon
    val links = remember(detail, bundled) { eventLinkPolicy(detail, bundled) }
    val demoNotice = stringResource(Res.string.demo_share_notice)
    val notSpecified = stringResource(Res.string.not_specified)
    var preview by remember(detail) { mutableStateOf<String?>(null) }
    var actionFailed by remember(detail) { mutableStateOf(false) }
    val onResult: (Boolean) -> Unit = { opened -> if (!opened) actionFailed = true }
    fun openWebsite(url: SafeHttpsUrl) {
        if (links.isDemo) preview = url.value else externalActions.openWebsite(url, onResult)
    }

    Column(Modifier.fillMaxSize().background(CatalogueColors.Background)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    AtlasIcon.Back, stringResource(Res.string.back), onBack,
                    tint = CatalogueColors.Text,
                    modifier = Modifier.clip(CircleShape).background(CatalogueColors.Surface),
                )
                Spacer(Modifier.weight(1f))
                if (event.isDemo) {
                    Text(
                        stringResource(Res.string.demo_label),
                        Modifier.border(1.dp, CatalogueColors.Border, RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, color = CatalogueColors.Text,
                    )
                }
                val bookmarkLabel = when {
                    saving -> Res.string.saving
                    saved -> Res.string.unsave_event
                    else -> Res.string.save_event
                }
                IconAction(
                    AtlasIcon.Bookmark, stringResource(bookmarkLabel), onBookmark,
                    tint = if (saved) CatalogueColors.Lime else CatalogueColors.Text, enabled = !saving,
                )
                IconAction(
                    AtlasIcon.Share, stringResource(Res.string.share),
                    {
                        links.shareEvent(event.title, demoNotice)?.let { externalActions.shareEvent(it, onResult) }
                    },
                    tint = CatalogueColors.Text, enabled = links.shareUrl != null,
                )
            }
            if (capturedAt != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(capturedAt)),
                        color = CatalogueColors.Turquoise, style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(Res.string.snapshot_note),
                        color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            DetailHero(event)
            DetailFacts(
                listOf(
                    DetailFact(AtlasIcon.Calendar, stringResource(Res.string.event_dates), format.range(event)),
                    DetailFact(
                        AtlasIcon.Clock, stringResource(Res.string.deadline), format.date(event.registrationDeadline),
                        format.exactTime(event.registrationDeadline),
                    ),
                    DetailFact(AtlasIcon.Pin, stringResource(Res.string.format), format.location(event)),
                    DetailFact(
                        AtlasIcon.People, stringResource(Res.string.event_organizer),
                        event.organizerName.ifBlank { notSpecified },
                    ),
                ),
            )
            DetailParagraph(stringResource(Res.string.about_event), detail.description)

            val teamSize = format.team(event.team)
            val teamLabel = if (event.team.minSize != null && event.team.maxSize != null) {
                teamSize + " " + stringResource(Res.string.people_suffix)
            } else teamSize
            DetailFacts(
                listOf(
                    DetailFact(AtlasIcon.Trophy, stringResource(Res.string.prize_fund), format.prize(event.prize)),
                    DetailFact(AtlasIcon.People, stringResource(Res.string.team_size), teamLabel),
                    DetailFact(
                        AtlasIcon.Tag, stringResource(Res.string.topics),
                        event.topics.joinToString(", ") { it.label }.ifBlank { notSpecified },
                    ),
                ),
            )
            ApplicationStatus(
                event.id, applications, format, bundled, onApplicationsRefresh,
                onApplicationsRecover, onApplicationChange, onApplicationSignIn,
            )
            DetailParagraph(stringResource(Res.string.eligibility), detail.eligibility.summary ?: notSpecified)

            if (detail.stages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(Res.string.stages))
                    detail.stages.forEach { stage ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlineIcon(AtlasIcon.Calendar, Modifier.size(19.dp), CatalogueColors.Turquoise)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stage.title, color = CatalogueColors.Text, style = MaterialTheme.typography.titleSmall)
                                Text(format.date(stage.whenAt), color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall)
                                format.exactTime(stage.whenAt)?.let {
                                    Text(it, color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            detail.submissionRequirements?.let { DetailParagraph(stringResource(Res.string.requirements), it) }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SectionTitle(stringResource(Res.string.source))
                if (bundled) {
                    Text(stringResource(Res.string.demo_source), color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    if (event.isDemo) {
                        Text(stringResource(Res.string.server_demo_body), color = CatalogueColors.Lime, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(detail.sourceUrl, color = CatalogueColors.Turquoise, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(Res.string.source_verified, event.lastVerifiedAt?.let(format::snapshotTimestamp) ?: stringResource(Res.string.not_verified)),
                    color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
                links.officialUrl?.let { url ->
                    TextButton(onClick = { openWebsite(url) }) {
                        Text(stringResource(Res.string.open_official_site), color = CatalogueColors.Turquoise)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val actionLabel = when (links.registrationState) {
            RegistrationActionState.AVAILABLE -> Res.string.register_cta
            RegistrationActionState.DEMO_PREVIEW -> Res.string.demo_link_cta
            RegistrationActionState.CLOSED -> Res.string.registration_closed
            RegistrationActionState.CANCELLED -> Res.string.event_cancelled
            RegistrationActionState.ENDED -> Res.string.event_ended
            RegistrationActionState.UNAVAILABLE -> Res.string.registration_unavailable
        }
        val registrationUrl = links.registrationUrl
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { registrationUrl?.let(::openWebsite) },
                enabled = registrationUrl != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CatalogueColors.Violet, contentColor = CatalogueColors.Text,
                    disabledContainerColor = CatalogueColors.Surface, disabledContentColor = CatalogueColors.Muted,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (registrationUrl != null) {
                    OutlineIcon(AtlasIcon.ExternalLink, Modifier.size(20.dp), CatalogueColors.Text)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(actionLabel), modifier = Modifier.weight(1f, fill = false),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                stringResource(if (links.isDemo) Res.string.demo_link_hint else Res.string.registration_external_hint),
                color = CatalogueColors.Muted, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            )
        }
    }
    if (preview != null || actionFailed) {
        val close = { preview = null; actionFailed = false }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(if (preview != null) Res.string.demo_link_title else Res.string.external_action_error)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(if (preview != null) Res.string.demo_link_body else Res.string.external_action_error_body),
                        color = CatalogueColors.Muted,
                    )
                    preview?.let { Text(it, color = CatalogueColors.Turquoise) }
                }
            },
            containerColor = CatalogueColors.Surface,
            titleContentColor = CatalogueColors.Text,
            confirmButton = {
                TextButton(onClick = close) {
                    Text(stringResource(Res.string.understood), color = CatalogueColors.Lime)
                }
            },
        )
    }
}

@Composable
private fun DetailHero(event: Hackathon) {
    val colors = eventCardColors(event.artwork)
    val topic = event.topics.joinToString(" / ") { it.label }
    val displayStyle = MaterialTheme.typography.displayMedium

    BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = 192.dp).clip(WaveCardShape).background(colors.surface),
        contentAlignment = Alignment.CenterStart,
    ) {
        val horizontalPadding = 16.dp
        val gap = 10.dp
        val artworkWidth = minOf(maxWidth * .34f, 144.dp)
        val textWidth = maxWidth - horizontalPadding * 2 - gap - artworkWidth
        val compact = maxWidth.value / LocalDensity.current.fontScale < 310f
        val titleStyle = if (compact) {
            displayStyle.copy(fontSize = 21.sp, lineHeight = 26.sp)
        } else {
            displayStyle.copy(fontSize = 23.sp, lineHeight = 31.sp)
        }
        val showArtwork = showEventArtwork(event.title, titleStyle, maxWidth, textWidth)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (topic.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Box(Modifier.size(4.dp, 13.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                        Text(topic, color = colors.text, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Text(
                    event.title, Modifier.semantics { heading() },
                    color = colors.text, style = titleStyle,
                )
            }
            if (showArtwork) {
                EventArtwork(event.artwork, colors.accent, Modifier.width(artworkWidth).aspectRatio(1.25f))
            }
        }
    }
}

private data class DetailFact(val icon: AtlasIcon, val label: String, val value: String, val note: String? = null)

@Composable
private fun DetailFacts(facts: List<DetailFact>) {
    val shape = RoundedCornerShape(22.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape).background(CatalogueColors.Surface)
            .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), shape),
    ) {
        val columns = if (maxWidth.value / LocalDensity.current.fontScale >= 310f) 2 else 1
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            facts.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { fact ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            OutlineIcon(fact.icon, Modifier.size(23.dp), CatalogueColors.Turquoise)
                            Text(fact.label, color = CatalogueColors.Muted, style = MaterialTheme.typography.labelMedium)
                            Text(fact.value, color = CatalogueColors.Text, style = MaterialTheme.typography.titleSmall)
                            fact.note?.let {
                                Text(it, color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailParagraph(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        SectionTitle(title)
        Text(body, color = CatalogueColors.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AtlasColors.TextPrimary)
}
