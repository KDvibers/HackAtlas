package dev.hackatlas.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun IconAction(icon: AtlasIcon, label: String, onClick: () -> Unit, tint: Color = AtlasColors.TextSecondary, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        OutlineIcon(icon, Modifier.size(22.dp), if (enabled) tint else AtlasColors.TextMuted)
    }
}

@Composable
fun DemoBadge(onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp)) {
        Text(stringResource(Res.string.demo_label), color = AtlasColors.Primary, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
    }
}

@Composable
fun CircuitDecoration(modifier: Modifier = Modifier, stronger: Boolean = false) {
    Canvas(modifier) {
        val grid = AtlasColors.Violet.copy(alpha = if (stronger) .18f else .09f)
        val step = 36.dp.toPx()
        for (i in 0..(size.width / step).toInt()) {
            val x = i * step + 8.dp.toPx()
            val start = ((i * 37) % 70).toFloat() / 100f * size.height
            drawLine(grid, Offset(x, start), Offset(x, (start + size.height * .4f).coerceAtMost(size.height)), 1.dp.toPx() * .5f)
            if (i % 3 == 0) {
                drawLine(grid, Offset(x, start), Offset((x + step * 1.7f).coerceAtMost(size.width), start), 1.dp.toPx() * .5f)
                drawCircle(AtlasColors.Primary.copy(alpha = .5f), 1.1.dp.toPx(), Offset(x, start))
            }
        }
        for (i in 1..4) {
            val y = size.height * i / 5f
            drawLine(grid, Offset(size.width * .42f, y), Offset(size.width, y), .5.dp.toPx())
        }
    }
}

@Composable
fun ArtworkView(artwork: Artwork, modifier: Modifier = Modifier, alpha: Float = 1f) {
    when (artwork) {
        Artwork.GREENTECH -> TurbineArtwork(modifier)
        else -> Image(
            painterResource(when (artwork) {
                Artwork.FINTECH_CITY -> Res.drawable.fintech_skyline
                Artwork.GAMEPAD -> Res.drawable.game_controller
                else -> Res.drawable.ai_head
            }), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, alpha = alpha,
        )
    }
}

@Composable
private fun TurbineArtwork(modifier: Modifier) {
    Canvas(modifier) {
        val cyan = AtlasColors.Cyan.copy(alpha = .65f)
        val violet = AtlasColors.Primary.copy(alpha = .5f)
        for (index in 0..2) {
            val x = size.width * (.26f + index * .24f)
            val y = size.height * (.34f + (index % 2) * .14f)
            val base = size.height * .94f
            drawLine(cyan, Offset(x, y), Offset(x - 3, base), 1.2.dp.toPx())
            drawLine(violet, Offset(x + 3, y), Offset(x + 5, base), .6.dp.toPx())
            val blade = size.width * .19f
            drawLine(cyan, Offset(x, y), Offset(x - blade, y - blade * .6f), 1.dp.toPx())
            drawLine(violet, Offset(x, y), Offset(x + blade, y - blade * .6f), 1.dp.toPx())
            drawLine(cyan, Offset(x, y), Offset(x, y + blade), 1.dp.toPx())
        }
        for (i in 0..6) drawLine(violet.copy(alpha = .2f), Offset(i * size.width / 6, size.height * .75f), Offset(i * size.width / 6, size.height), .5.dp.toPx())
    }
}

@Composable
fun MetaLine(icon: AtlasIcon, text: String, modifier: Modifier = Modifier, color: Color = AtlasColors.TextSecondary) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlineIcon(icon, Modifier.size(15.dp), AtlasColors.Violet)
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun HackathonCard(event: Hackathon, saved: Boolean, format: DisplayFormat, onOpen: () -> Unit, onBookmark: () -> Unit, bookmarkEnabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(AtlasColors.Surface).border(1.dp, AtlasColors.Border, shape)
        .clickable(role = Role.Button, onClick = onOpen)) {
        Box(Modifier.matchParentSize()) {
            CircuitDecoration(Modifier.matchParentSize())
            ArtworkView(event.artwork, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(163.dp).padding(top = 7.dp, end = 4.dp, bottom = 3.dp), .83f)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to AtlasColors.Surface, .40f to AtlasColors.Surface.copy(alpha = .91f), .82f to Color.Transparent)))
        }
        Column(Modifier.padding(start = 14.dp, top = 11.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TechLabel("— ${event.topics.firstOrNull()?.label?.uppercase().orEmpty()} —" + if (event.isDemo) " · ${stringResource(Res.string.demo_label)}" else "", Modifier.padding(end = 40.dp))
            Text(event.title, Modifier.fillMaxWidth(.84f), style = MaterialTheme.typography.titleLarge, color = AtlasColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(AtlasIcon.Calendar, format.range(event), Modifier.fillMaxWidth(.76f))
            MetaLine(AtlasIcon.Pin, format.location(event), Modifier.fillMaxWidth(.7f))
            MetaLine(AtlasIcon.Trophy, format.prize(event.prize), Modifier.fillMaxWidth(.7f), AtlasColors.TextPrimary)
            Text("${stringResource(Res.string.deadline_short)} ${format.date(event.registrationDeadline, false)}", Modifier.fillMaxWidth(.73f), color = AtlasColors.TextMuted, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
        }
        IconAction(AtlasIcon.Bookmark, stringResource(if (!bookmarkEnabled) Res.string.saving else if (saved) Res.string.unsave_event else Res.string.save_event), onBookmark,
            if (saved) AtlasColors.Primary else AtlasColors.TextSecondary, Modifier.align(Alignment.TopEnd), enabled = bookmarkEnabled)
        Text(stringResource(when (event.artwork) {
            Artwork.FINTECH_CITY -> Res.string.card_fintech_kicker
            Artwork.GAMEPAD -> Res.string.card_gamedev_kicker
            Artwork.GREENTECH -> Res.string.card_green_kicker
            else -> Res.string.card_ai_kicker
        }), Modifier.align(Alignment.BottomEnd).padding(9.dp).background(AtlasColors.Background.copy(alpha = .6f), RoundedCornerShape(3.dp)).padding(2.dp), color = AtlasColors.Cyan.copy(alpha = .85f), fontSize = 7.sp, lineHeight = 8.sp, letterSpacing = .5.sp)
    }
}

@Composable
fun HackathonMiniCard(event: Hackathon, format: DisplayFormat, onOpen: () -> Unit, onRemove: () -> Unit, showDemoLabel: Boolean = true) {
    val shape = RoundedCornerShape(11.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(AtlasColors.Surface).border(1.dp, AtlasColors.Border.copy(alpha = .65f), shape).clickable(role = Role.Button, onClick = onOpen)) {
        ArtworkView(event.artwork, Modifier.align(Alignment.CenterEnd).width(105.dp).height(75.dp).padding(end = 28.dp), .66f)
        Row(Modifier.fillMaxWidth().padding(start = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (showDemoLabel && event.isDemo) Text(stringResource(Res.string.demo_label), color = AtlasColors.Primary, fontSize = 9.sp)
                Text(event.title, Modifier.fillMaxWidth(.84f), style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("${format.range(event)} · ${format.location(event)}", Modifier.fillMaxWidth(.82f), color = AtlasColors.TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
            }
            IconAction(AtlasIcon.Bookmark, stringResource(Res.string.unsave_event), onRemove, AtlasColors.Primary)
        }
    }
}

@Composable
fun InfoItem(icon: AtlasIcon, title: String, value: String, note: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 11.dp, horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineIcon(icon, Modifier.padding(top = 3.dp).size(19.dp), AtlasColors.Primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = AtlasColors.TextMuted, fontSize = 10.sp, lineHeight = 13.sp)
            Text(value, color = AtlasColors.TextPrimary, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
            if (note != null) Text(note, color = AtlasColors.TextSecondary, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
fun InterestChip(label: String) {
    Text(label, Modifier.border(1.dp, AtlasColors.Border, RoundedCornerShape(20.dp)).background(AtlasColors.Primary.copy(alpha = .055f), RoundedCornerShape(20.dp)).padding(horizontal = 13.dp, vertical = 7.dp), fontSize = 12.sp, lineHeight = 17.sp, color = AtlasColors.TextPrimary)
}

@Composable
fun StatePanel(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlineIcon(AtlasIcon.Sparkles, Modifier.size(32.dp), AtlasColors.Primary)
        Text(title, style = MaterialTheme.typography.titleLarge, color = AtlasColors.TextPrimary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = AtlasColors.TextSecondary)
        if (action != null) NeonButton(action, onAction, Modifier.fillMaxWidth())
    }
}
