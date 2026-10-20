package dev.hackatlas.mobile.design

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.resources.Res
import dev.hackatlas.mobile.resources.exo_2_bold
import dev.hackatlas.mobile.resources.inter_bold
import dev.hackatlas.mobile.resources.inter_regular
import dev.hackatlas.mobile.resources.inter_semibold
import dev.hackatlas.mobile.resources.jetbrains_mono_regular
import dev.hackatlas.mobile.resources.press_start_2p
import org.jetbrains.compose.resources.Font

/** Initial tokens from design/tokens.json. Visual baseline still requires approval. */
object AtlasColors {
    val Background = Color(0xFF050507)
    val Surface = Color(0xFF0D0B13)
    val SurfaceRaised = Color(0xFF14101C)
    val Border = Color(0xFF443053)
    val Primary = Color(0xFFE91DF4)
    val PrimaryPressed = Color(0xFFC916D4)
    val Violet = Color(0xFFA855F7)
    val Cyan = Color(0xFF63D8FF)
    val TextPrimary = Color(0xFFF5F2FA)
    val TextSecondary = Color(0xFFC0B8CC)
    val TextMuted = Color(0xFF9F97AD)
    val Danger = Color(0xFFFF6B89)
    val Success = Color(0xFF7ED5AC)
}

object AtlasDimensions {
    val ScreenPadding = 20.dp
    val CardRadius = 14.dp
    val FieldRadius = 14.dp
    val ButtonRadius = 12.dp
    val ChipRadius = 20.dp
    val Outline = 1.dp
    val MinimumTouchTarget = 48.dp
}

@Composable
fun HackAtlasTheme(content: @Composable () -> Unit) {
    val body = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
        Font(Res.font.inter_bold, FontWeight.Bold),
    )
    val event = FontFamily(Font(Res.font.exo_2_bold, FontWeight.Bold))
    val pixel = FontFamily(Font(Res.font.press_start_2p))
    val tech = FontFamily(Font(Res.font.jetbrains_mono_regular))
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AtlasColors.Primary,
            onPrimary = AtlasColors.Background,
            primaryContainer = AtlasColors.SurfaceRaised,
            onPrimaryContainer = AtlasColors.TextPrimary,
            secondary = AtlasColors.Cyan,
            onSecondary = AtlasColors.Background,
            background = AtlasColors.Background,
            onBackground = AtlasColors.TextPrimary,
            surface = AtlasColors.Surface,
            onSurface = AtlasColors.TextPrimary,
            surfaceVariant = AtlasColors.SurfaceRaised,
            onSurfaceVariant = AtlasColors.TextSecondary,
            outline = AtlasColors.Border,
            error = AtlasColors.Danger,
        ),
        typography = Typography(
            displayLarge = TextStyle(fontFamily = pixel, fontSize = 28.sp, lineHeight = 38.sp),
            displayMedium = TextStyle(fontFamily = pixel, fontSize = 26.sp, lineHeight = 36.sp),
            displaySmall = TextStyle(fontFamily = pixel, fontSize = 23.sp, lineHeight = 32.sp),
            headlineLarge = TextStyle(fontFamily = event, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
            headlineMedium = TextStyle(fontFamily = event, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
            headlineSmall = TextStyle(fontFamily = event, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp),
            titleLarge = TextStyle(fontFamily = event, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
            titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
            titleSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontFamily = body, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = body, fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontFamily = body, fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = body, fontSize = 12.sp, lineHeight = 17.sp),
            labelSmall = TextStyle(fontFamily = tech, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(AtlasDimensions.ButtonRadius),
            medium = RoundedCornerShape(AtlasDimensions.CardRadius),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

/** Actual Cyrillic font, with a small chromatic edge; never a bitmap heading. */
@Composable
fun PixelHeading(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 26.sp) {
    Text(
        text = text,
        modifier = modifier,
        color = AtlasColors.TextPrimary,
        style = MaterialTheme.typography.displayMedium.copy(
            fontFamily = FontFamily(Font(Res.font.press_start_2p)),
            fontSize = fontSize,
            lineHeight = fontSize * 1.4f,
            shadow = Shadow(AtlasColors.Primary, Offset(1.5f, 1.5f), 0f),
        ),
    )
}

@Composable
fun TechLabel(text: String, modifier: Modifier = Modifier, color: Color = AtlasColors.Cyan) {
    Text(text, modifier = modifier, color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
fun NeonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(AtlasDimensions.ButtonRadius)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 52.dp).shadow(
            elevation = if (enabled) 14.dp else 0.dp,
            shape = shape,
            ambientColor = AtlasColors.Primary.copy(alpha = 0.55f),
            spotColor = AtlasColors.Primary.copy(alpha = 0.6f),
        ),
        shape = shape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AtlasColors.Primary,
            contentColor = AtlasColors.Background,
            disabledContainerColor = AtlasColors.SurfaceRaised,
            disabledContentColor = AtlasColors.TextMuted,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            OutlineIcon(AtlasIcon.ArrowRight, modifier = Modifier.size(21.dp), tint = if (enabled) AtlasColors.Background else AtlasColors.TextMuted)
        }
    }
}
