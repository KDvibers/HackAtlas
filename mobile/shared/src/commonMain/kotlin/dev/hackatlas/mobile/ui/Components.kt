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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
fun HackathonMiniCard(
    event: Hackathon,
    format: DisplayFormat,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    showDemoLabel: Boolean = true,
    removeEnabled: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    val colors = eventCardColors(event.artwork)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(CatalogueColors.Surface)
            .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), shape)
            .clickable(role = Role.Button, onClick = onOpen).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).background(colors.surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            EventArtwork(event.artwork, colors.accent, Modifier.size(width = 38.dp, height = 31.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showDemoLabel && event.isDemo) {
                Text(
                    stringResource(Res.string.demo_label), color = CatalogueColors.Turquoise,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                event.title, color = CatalogueColors.Text, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                format.range(event) + " · " + format.location(event),
                color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        IconAction(
            AtlasIcon.Bookmark, stringResource(Res.string.unsave_event), onRemove,
            tint = CatalogueColors.Lime, enabled = removeEnabled,
        )
    }
}

@Composable
fun InterestChip(label: String) {
    val shape = RoundedCornerShape(20.dp)
    Text(
        label, Modifier.background(CatalogueColors.Surface, shape).border(1.dp, CatalogueColors.Border, shape)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        color = CatalogueColors.Text, style = MaterialTheme.typography.labelMedium,
    )
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
