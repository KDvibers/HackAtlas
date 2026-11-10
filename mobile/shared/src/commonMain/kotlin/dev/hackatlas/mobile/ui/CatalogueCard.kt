package dev.hackatlas.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.domain.Artwork
import dev.hackatlas.mobile.domain.Hackathon
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

internal object CatalogueColors {
    val Background = Color(0xFF1B1B27)
    val Surface = Color(0xFF2C2D3C)
    val Border = Color(0xFF55566A)
    val Text = Color(0xFFF8F6FF)
    val Muted = Color(0xFFC6C3D5)
    val Ink = Color(0xFF15151E)
    val Lime = Color(0xFFDAFF32)
    val Violet = Color(0xFF6734D7)
    val Turquoise = Color(0xFF79F1ED)
}

private data class CardColors(val surface: Color, val text: Color, val accent: Color)

private val AiCard = CardColors(CatalogueColors.Lime, CatalogueColors.Ink, Color(0xFF5C2ABD))
private val FintechCard = CardColors(CatalogueColors.Violet, CatalogueColors.Text, CatalogueColors.Lime)
private val GraphiteCard = CardColors(Color(0xFF38394C), CatalogueColors.Text, CatalogueColors.Turquoise)

private val WaveCardShape = GenericShape { size, _ ->
    val (width, height) = size
    val radius = minOf(width * .075f, height * .17f)
    val wave = minOf(width * .022f, height * .045f)
    val right = width - radius
    val bottom = height - wave

    moveTo(radius, wave)
    cubicTo(width * .26f, 0f, width * .44f, wave * 2f, width * .60f, wave)
    cubicTo(width * .75f, 0f, width * .86f, 0f, right, wave)
    cubicTo(width, wave, width, wave + radius * .45f, width, wave + radius)
    lineTo(width, bottom - radius)
    cubicTo(width, bottom - radius * .45f, width, bottom, right, bottom)
    cubicTo(width * .78f, height, width * .60f, bottom - wave, width * .43f, bottom)
    cubicTo(width * .29f, height, width * .15f, height, radius, bottom)
    cubicTo(0f, bottom, 0f, bottom - radius * .45f, 0f, bottom - radius)
    lineTo(0f, wave + radius)
    cubicTo(0f, wave + radius * .45f, 0f, wave, radius, wave)
    close()
}

@Composable
internal fun CatalogueCard(
    event: Hackathon,
    saved: Boolean,
    format: DisplayFormat,
    onOpen: () -> Unit,
    onBookmark: () -> Unit,
    bookmarkEnabled: Boolean,
) {
    val colors = when (event.artwork) {
        Artwork.AI_BUST -> AiCard
        Artwork.FINTECH_CITY -> FintechCard
        else -> GraphiteCard
    }
    val demoLabel = if (event.isDemo) stringResource(Res.string.demo_label) else null
    val topic = event.topics.firstOrNull()?.label?.takeIf { it.isNotBlank() }?.uppercase()
    val label = listOfNotNull(topic, demoLabel).joinToString(" · ")
    val deadline = stringResource(Res.string.deadline_short)
    val titleStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 21.sp, lineHeight = 26.sp)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val longestWord = event.title.split(' ', '\n', '\t').maxByOrNull { it.length }.orEmpty()

    BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = 174.dp).clip(WaveCardShape).background(colors.surface)
            .clickable(role = Role.Button, onClick = onOpen),
        contentAlignment = Alignment.CenterStart,
    ) {
        val horizontalPadding = 16.dp
        val artworkGap = 10.dp
        val artworkWidth = minOf(maxWidth * .33f, 132.dp)
        val textWidthBesideArtwork = maxWidth - artworkWidth - horizontalPadding * 2 - artworkGap
        val showArtwork = maxWidth.value / density.fontScale >= 310f && with(density) {
            val wordWidth = measurer.measure(AnnotatedString(longestWord), titleStyle, softWrap = false, maxLines = 1).size.width
            wordWidth <= textWidthBesideArtwork.roundToPx()
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(artworkGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (label.isNotEmpty() || !showArtwork) {
                    // On text-only cards the bookmark shares the category row, above the title.
                    Row(
                        Modifier.padding(end = if (showArtwork) 0.dp else 36.dp)
                            .heightIn(min = if (showArtwork) 0.dp else 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (label.isNotEmpty()) {
                            Box(Modifier.size(4.dp, 12.dp).background(colors.accent, RoundedCornerShape(2.dp)))
                            Text(
                                label, color = colors.text, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    event.title, color = colors.text, style = titleStyle,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                val metadataStyle = MaterialTheme.typography.bodySmall
                Text(
                    listOf(format.range(event), format.location(event)).joinToString(" · "),
                    color = colors.text, style = metadataStyle,
                )
                Text(
                    "$deadline " + format.date(event.registrationDeadline, false),
                    color = colors.text, style = metadataStyle,
                )
                Text(format.prize(event.prize), color = colors.text, style = metadataStyle)
            }
            if (showArtwork) {
                CatalogueArtwork(event.artwork, colors.accent, Modifier.width(artworkWidth).aspectRatio(1.25f))
            }
        }
        val bookmarkLabel = when {
            !bookmarkEnabled -> Res.string.saving
            saved -> Res.string.unsave_event
            else -> Res.string.save_event
        }
        IconAction(
            AtlasIcon.Bookmark, stringResource(bookmarkLabel), onBookmark,
            tint = if (saved) colors.accent else colors.text,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
            enabled = bookmarkEnabled,
        )
    }
}

@Composable
private fun CatalogueArtwork(artwork: Artwork, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        scale(size.width / 100f, size.height / 80f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val center = Offset(50f, 40f)
            when (artwork) {
                Artwork.AI_BUST -> {
                    rotate(-27f, center) {
                        drawOval(accent, Offset(5f, 19f), Size(90f, 42f), style = stroke)
                        drawOval(accent, Offset(13f, 26f), Size(74f, 28f), style = stroke)
                        drawCircle(accent, 4.5f, Offset(24f, 24f))
                    }
                    drawCircle(CatalogueColors.Turquoise, 8f, Offset(58f, 38f))
                }
                Artwork.FINTECH_CITY -> rotate(-12f, center) {
                    drawRoundRect(accent, Offset(8f, 15f), Size(84f, 50f), CornerRadius(7f), style = stroke)
                    drawRoundRect(accent, Offset(17f, 32f), Size(15f, 12f), CornerRadius(2f), style = stroke)
                    drawLine(accent, Offset(24.5f, 33f), Offset(24.5f, 43f), 2f)
                    drawLine(accent, Offset(51f, 36f), Offset(81f, 36f), 2.5f, StrokeCap.Round)
                    drawLine(accent, Offset(51f, 46f), Offset(76f, 46f), 2.5f, StrokeCap.Round)
                }
                Artwork.GAMEPAD -> {
                    val outline = Path().apply {
                        moveTo(31f, 18f)
                        cubicTo(17f, 15f, 11f, 27f, 6f, 46f)
                        cubicTo(2f, 61f, 8f, 69f, 20f, 62f)
                        cubicTo(32f, 54f, 33f, 49f, 50f, 49f)
                        cubicTo(67f, 49f, 68f, 54f, 80f, 62f)
                        cubicTo(92f, 69f, 98f, 61f, 94f, 46f)
                        cubicTo(89f, 27f, 83f, 15f, 69f, 18f)
                        cubicTo(59f, 20f, 41f, 20f, 31f, 18f)
                        close()
                    }
                    drawPath(outline, accent, style = stroke)
                    drawLine(accent, Offset(22f, 36f), Offset(38f, 36f), 3f, StrokeCap.Round)
                    drawLine(accent, Offset(30f, 28f), Offset(30f, 44f), 3f, StrokeCap.Round)
                    drawCircle(accent, 2.8f, Offset(71f, 29f))
                    drawCircle(accent, 2.8f, Offset(71f, 43f))
                    drawCircle(accent, 2.8f, Offset(64f, 36f))
                    drawCircle(accent, 2.8f, Offset(78f, 36f))
                }
                Artwork.GREENTECH -> {
                    val leaf = Path().apply {
                        moveTo(20f, 62f)
                        cubicTo(12f, 23f, 52f, 15f, 83f, 12f)
                        cubicTo(80f, 48f, 60f, 70f, 20f, 62f)
                        close()
                    }
                    drawPath(leaf, accent, style = stroke)
                    drawLine(accent, Offset(14f, 69f), Offset(67f, 28f), 2.5f, StrokeCap.Round)
                }
                Artwork.WIREFRAME -> {
                    drawCircle(accent, 29f, center, style = stroke)
                    val needle = Path().apply {
                        moveTo(66f, 24f)
                        lineTo(56f, 46f)
                        lineTo(34f, 56f)
                        lineTo(44f, 34f)
                        close()
                    }
                    drawPath(needle, accent, style = stroke)
                }
            }
        }
    }
}
