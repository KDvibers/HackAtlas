package dev.hackatlas.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun TypographyScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(AtlasIcon.Back, stringResource(Res.string.back), onBack)
            SectionTitle(stringResource(Res.string.typography))
        }
        Text(stringResource(Res.string.typography_subtitle), style = MaterialTheme.typography.bodyMedium, color = AtlasColors.TextSecondary)
        TechLabel(stringResource(Res.string.pixel_font_label))
        PixelHeading(stringResource(Res.string.hackathons), fontSize = 29.sp)
        PixelHeading(stringResource(Res.string.profile), fontSize = 29.sp)
        PixelHeading(stringResource(Res.string.saved), fontSize = 23.sp)
        PixelHeading(stringResource(Res.string.font_sample), fontSize = 16.sp)
        PixelHeading(stringResource(Res.string.font_money), fontSize = 20.sp)
        HorizontalDivider(color = AtlasColors.Border)
        TechLabel(stringResource(Res.string.event_font_label))
        Text("AI Sprint 2026", style = MaterialTheme.typography.titleLarge, fontSize = 28.sp)
        TechLabel(stringResource(Res.string.body_font_label))
        Text(stringResource(Res.string.font_body_sample), style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, lineHeight = 24.sp)
        Text(stringResource(Res.string.font_money), style = MaterialTheme.typography.bodyLarge, fontSize = 22.sp)
        Text(stringResource(Res.string.font_note), style = MaterialTheme.typography.bodySmall, color = AtlasColors.TextMuted, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
    }
}
