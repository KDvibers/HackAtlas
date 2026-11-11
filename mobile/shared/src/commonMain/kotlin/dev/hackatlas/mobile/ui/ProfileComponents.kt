package dev.hackatlas.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.design.OutlineIcon
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ProfileList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(CatalogueColors.Background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun ProfileHeader(onSettings: (() -> Unit)? = null, onDemo: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                HackAtlasBrand(showDemo = onDemo != null, onDemo = onDemo)
            }
            if (onSettings != null) {
                IconAction(
                    AtlasIcon.Settings, stringResource(Res.string.settings), onSettings,
                    tint = CatalogueColors.Muted,
                )
            }
        }
        Text(
            stringResource(Res.string.profile), Modifier.semantics { heading() },
            color = CatalogueColors.Text, style = MaterialTheme.typography.displayLarge,
        )
    }
}

@Composable
internal fun ProfilePanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(WaveCardShape).background(CatalogueColors.Surface)
            .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), WaveCardShape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun ProfileIdentity(
    name: String,
    initial: String? = null,
    hooded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(CatalogueColors.Violet),
            contentAlignment = Alignment.Center,
        ) {
            when {
                hooded -> Image(
                    painterResource(Res.drawable.demo_avatar), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
                initial.isNullOrBlank() -> OutlineIcon(AtlasIcon.User, Modifier.size(30.dp), CatalogueColors.Text)
                else -> Text(initial, color = CatalogueColors.Text, style = MaterialTheme.typography.displaySmall)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, color = CatalogueColors.Text, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileStatistics(stats: List<Pair<Int, String>>) {
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 24.sp)
    val labelStyle = MaterialTheme.typography.labelMedium
    val widestText = stats.maxOfOrNull { (value, label) ->
        maxOf(
            measurer.measure(AnnotatedString(value.toString()), valueStyle, softWrap = false, maxLines = 1).size.width,
            measurer.measure(AnnotatedString(label), labelStyle, softWrap = false, maxLines = 1).size.width,
        )
    } ?: return
    val spacing = 6.dp
    val tileHorizontalPadding = 4.dp
    val minTileWidth = with(LocalDensity.current) { widestText.toDp() } + tileHorizontalPadding * 2 + 1.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + spacing) / (minTileWidth + spacing)).toInt().coerceIn(1, stats.size)
        FlowRow(
            Modifier.fillMaxWidth(), maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            stats.forEach { (value, label) ->
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp).background(CatalogueColors.Background, RoundedCornerShape(12.dp))
                        .padding(horizontal = tileHorizontalPadding, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(value.toString(), color = CatalogueColors.Lime, style = valueStyle)
                    Text(label, color = CatalogueColors.Muted, style = labelStyle, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun ProfileSectionHeading(
    icon: AtlasIcon,
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlineIcon(icon, Modifier.size(18.dp), CatalogueColors.Turquoise)
        Text(
            title, Modifier.weight(1f).semantics { heading() }, color = CatalogueColors.Text,
            style = MaterialTheme.typography.titleMedium,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CatalogueColors.Lime),
            ) { Text(actionLabel) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileInterests(labels: List<String>, onEdit: (() -> Unit)? = null) {
    Column {
        ProfileSectionHeading(
            AtlasIcon.Sparkles, stringResource(Res.string.interests), stringResource(Res.string.edit), onEdit,
        )
        if (labels.isEmpty()) {
            Text(
                stringResource(Res.string.auth_no_interests), color = CatalogueColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEach { InterestChip(it) }
            }
        }
    }
}

@Composable
internal fun ProfilePrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CatalogueColors.Lime, contentColor = CatalogueColors.Ink),
    ) { Text(text) }
}
