package dev.hackatlas.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.design.OutlineIcon
import dev.hackatlas.mobile.domain.SavedEventKey
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun SavedScreen(
    snapshots: List<SavedGuestEvent>,
    format: DisplayFormat,
    onOpen: (SavedGuestEvent) -> Unit,
    onRemove: (SavedGuestEvent) -> Unit,
    onCatalog: () -> Unit,
    onDemo: () -> Unit,
    bundled: Boolean = true,
    accountMode: Boolean = false,
    showEmpty: Boolean = true,
    removeEnabled: Boolean = true,
    pendingKeys: Set<SavedEventKey> = emptySet(),
    statusContent: @Composable () -> Unit = {},
) {
    LazyColumn(
        Modifier.fillMaxSize().background(CatalogueColors.Background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HackAtlasBrand(bundled, onDemo)
                    Text(
                        stringResource(Res.string.saved),
                        Modifier.semantics { heading() },
                        color = CatalogueColors.Text,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 28.sp, lineHeight = 39.sp),
                    )
                    Text(
                        stringResource(if (accountMode) Res.string.account_saved_subtitle else Res.string.saved_local_subtitle),
                        color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshots.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.snapshot_note),
                            color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                statusContent()
            }
        }
        if (snapshots.isEmpty() && showEmpty) {
            item(key = "empty") { SavedEmpty(onCatalog) }
        }
        items(snapshots, key = { it.key.listKey() }) { snapshot ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CatalogueCard(
                    event = snapshot.detail.hackathon,
                    saved = true,
                    format = format,
                    onOpen = { onOpen(snapshot) },
                    onBookmark = { onRemove(snapshot) },
                    bookmarkPending = removeEnabled && snapshot.key in pendingKeys,
                    bookmarkEnabled = removeEnabled,
                    showDemoLabel = !bundled,
                )
                Text(
                    stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(snapshot.savedAt)),
                    Modifier.padding(horizontal = 16.dp),
                    color = CatalogueColors.Muted, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SavedEmpty(onCatalog: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().background(CatalogueColors.Surface, shape)
            .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), shape).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlineIcon(AtlasIcon.Bookmark, Modifier.size(28.dp), CatalogueColors.Turquoise)
        Text(
            stringResource(Res.string.empty_saved), color = CatalogueColors.Text,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.empty_saved_body), color = CatalogueColors.Muted,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        Button(
            onClick = onCatalog,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CatalogueColors.Violet, contentColor = CatalogueColors.Text),
        ) {
            Text(stringResource(Res.string.go_catalog))
        }
    }
}
