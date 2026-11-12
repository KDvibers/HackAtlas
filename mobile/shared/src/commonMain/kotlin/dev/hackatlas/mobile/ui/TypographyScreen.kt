package dev.hackatlas.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.auth.AuthScreenLayout
import org.jetbrains.compose.resources.stringResource

@Composable
fun TypographyScreen(onBack: () -> Unit) {
    val typography = MaterialTheme.typography
    val headingModifier = Modifier.semantics { heading() }

    AuthScreenLayout(stringResource(Res.string.typography), onBack) {
        Text(
            stringResource(Res.string.typography_subtitle),
            color = CatalogueColors.Muted, style = typography.bodyMedium,
        )
        ProfilePanel {
            Text(
                stringResource(Res.string.pixel_font_label), headingModifier,
                color = CatalogueColors.Lime, style = typography.titleMedium,
            )
            PixelFontSample(stringResource(Res.string.hackathons), typography.displayLarge.fontSize)
            PixelFontSample(stringResource(Res.string.profile), typography.displayLarge.fontSize)
            PixelFontSample(stringResource(Res.string.saved), typography.displaySmall.fontSize)
            Text(
                stringResource(Res.string.font_sample), color = CatalogueColors.Text,
                style = typography.displaySmall.copy(fontSize = 16.sp, lineHeight = 24.sp),
            )
            PixelFontSample(stringResource(Res.string.font_money), 20.sp)
        }
        ProfilePanel(containerColor = CatalogueColors.Violet) {
            Text(
                stringResource(Res.string.event_font_label), headingModifier,
                color = CatalogueColors.Text, style = typography.titleMedium,
            )
            Text("AI Sprint 2026", color = CatalogueColors.Text, style = typography.headlineLarge)
        }
        ProfilePanel {
            Text(
                stringResource(Res.string.body_font_label), headingModifier,
                color = CatalogueColors.Turquoise, style = typography.titleMedium,
            )
            Text(stringResource(Res.string.font_body_sample), color = CatalogueColors.Text, style = typography.bodyLarge)
            Text(
                stringResource(Res.string.font_money), color = CatalogueColors.Text,
                style = typography.bodyLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
            )
        }
    }
}

@Composable
private fun PixelFontSample(text: String, maxFontSize: TextUnit) {
    Text(
        text, Modifier.fillMaxWidth(),
        color = CatalogueColors.Text,
        style = MaterialTheme.typography.displaySmall.copy(lineHeight = TextUnit.Unspecified),
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = maxFontSize, stepSize = 1.sp),
        maxLines = 1,
    )
}
