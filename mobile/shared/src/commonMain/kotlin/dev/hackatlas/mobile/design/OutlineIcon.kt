package dev.hackatlas.mobile.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small original 24-unit outline icon set. No font/emoji or external icon dependency. */
enum class AtlasIcon {
    Home, Calendar, Bookmark, User, Search, Back, Share, Settings, Pin, Trophy,
    Clock, People, Tag, ArrowRight, ExternalLink, Close, Edit, More, Check,
    ChevronRight, Sparkles,
}

@Composable
fun OutlineIcon(
    icon: AtlasIcon,
    modifier: Modifier = Modifier.size(22.dp),
    tint: Color = AtlasColors.TextSecondary,
    contentDescription: String? = null,
) {
    val accessibleModifier = if (contentDescription == null) modifier else modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(accessibleModifier) {
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(tint, Offset(x1, y1), Offset(x2, y2), 1.6f, cap = StrokeCap.Round)
            fun circle(x: Float, y: Float, radius: Float) = drawCircle(tint, radius, Offset(x, y), style = stroke)
            fun poly(vararg points: Float, closed: Boolean = false) {
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    var i = 2
                    while (i < points.size) { lineTo(points[i], points[i + 1]); i += 2 }
                    if (closed) close()
                }
                drawPath(path, tint, style = stroke)
            }
            fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.5f) =
                drawRoundRect(tint, Offset(x, y), Size(w, h), CornerRadius(r), style = stroke)
            when (icon) {
                AtlasIcon.Home -> {
                    poly(3f, 10f, 12f, 3f, 21f, 10f)
                    poly(5f, 9f, 5f, 21f, 10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f, 19f, 21f, 19f, 9f)
                }
                AtlasIcon.Calendar -> {
                    rect(4f, 5f, 16f, 16f); line(4f, 10f, 20f, 10f)
                    line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f)
                    line(8f, 14f, 9f, 14f); line(14f, 14f, 15f, 14f); line(8f, 17f, 9f, 17f)
                }
                AtlasIcon.Bookmark -> poly(6f, 3f, 18f, 3f, 18f, 21f, 12f, 17f, 6f, 21f, closed = true)
                AtlasIcon.User -> {
                    circle(12f, 7f, 4f)
                    val path = Path().apply { moveTo(4f, 21f); lineTo(4f, 19f); cubicTo(4f, 12f, 20f, 12f, 20f, 19f); lineTo(20f, 21f) }
                    drawPath(path, tint, style = stroke)
                }
                AtlasIcon.Search -> { circle(10f, 10f, 6.5f); line(15f, 15f, 21f, 21f) }
                AtlasIcon.Back -> { poly(10f, 5f, 3f, 12f, 10f, 19f); line(3f, 12f, 21f, 12f) }
                AtlasIcon.Share -> {
                    poly(5f, 10f, 5f, 21f, 19f, 21f, 19f, 10f)
                    line(12f, 3f, 12f, 15f); poly(8f, 7f, 12f, 3f, 16f, 7f)
                }
                AtlasIcon.Settings -> {
                    circle(12f, 12f, 3f)
                    val gear = Path()
                    for (i in 0 until 32) {
                        val angle = i * (2 * PI / 32) - PI / 2
                        val radius = if (i % 4 < 2) 9f else 7.2f
                        val x = 12f + (cos(angle) * radius).toFloat()
                        val y = 12f + (sin(angle) * radius).toFloat()
                        if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                    }
                    gear.close(); drawPath(gear, tint, style = stroke)
                }
                AtlasIcon.Pin -> {
                    val path = Path().apply { moveTo(12f, 22f); cubicTo(10f, 18f, 4f, 13f, 4f, 9f); cubicTo(4f, -1f, 20f, -1f, 20f, 9f); cubicTo(20f, 13f, 14f, 18f, 12f, 22f); close() }
                    drawPath(path, tint, style = stroke); circle(12f, 8f, 2.8f)
                }
                AtlasIcon.Trophy -> {
                    poly(7f, 3f, 7f, 10f, 9f, 14f, 15f, 14f, 17f, 10f, 17f, 3f, closed = true)
                    poly(7f, 5f, 3f, 5f, 3f, 9f, 5f, 11f, 7f, 11f)
                    poly(17f, 5f, 21f, 5f, 21f, 9f, 19f, 11f, 17f, 11f)
                    line(12f, 14f, 12f, 20f); line(7f, 21f, 17f, 21f)
                }
                AtlasIcon.Clock -> { circle(12f, 12f, 9f); poly(12f, 6f, 12f, 12f, 16f, 14f) }
                AtlasIcon.People -> {
                    circle(9f, 7f, 3.5f); poly(2f, 21f, 2f, 18f, 5f, 14f, 13f, 14f, 16f, 18f, 16f, 21f)
                    poly(16f, 4f, 19f, 5f, 20f, 8f, 18f, 11f)
                    poly(18f, 14f, 22f, 17f, 22f, 21f)
                }
                AtlasIcon.Tag -> {
                    poly(3f, 3f, 12f, 3f, 22f, 13f, 13f, 22f, 3f, 12f, closed = true); circle(7.5f, 7.5f, 1.2f)
                }
                AtlasIcon.ArrowRight -> { line(3f, 12f, 21f, 12f); poly(14f, 5f, 21f, 12f, 14f, 19f) }
                AtlasIcon.ExternalLink -> {
                    poly(10f, 4f, 4f, 4f, 4f, 20f, 20f, 20f, 20f, 14f)
                    poly(14f, 3f, 21f, 3f, 21f, 10f); line(11f, 13f, 21f, 3f)
                }
                AtlasIcon.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                AtlasIcon.Edit -> {
                    poly(4f, 15f, 4f, 20f, 9f, 20f, 21f, 8f, 16f, 3f, closed = true)
                    line(13f, 6f, 18f, 11f); line(4f, 15f, 9f, 20f)
                }
                AtlasIcon.More -> { for (y in listOf(5f, 12f, 19f)) drawCircle(tint, 1.5f, Offset(12f, y)) }
                AtlasIcon.Check -> poly(4f, 12f, 9f, 17f, 20f, 6f)
                AtlasIcon.ChevronRight -> poly(9f, 5f, 16f, 12f, 9f, 19f)
                AtlasIcon.Sparkles -> {
                    poly(10f, 3f, 12f, 9f, 18f, 11f, 12f, 13f, 10f, 19f, 8f, 13f, 2f, 11f, 8f, 9f, closed = true)
                    line(19f, 3f, 19f, 7f); line(17f, 5f, 21f, 5f)
                    line(20f, 17f, 20f, 21f); line(18f, 19f, 22f, 19f)
                }
            }
        }
    }
}
