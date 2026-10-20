package dev.hackatlas.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.Hackathon
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CatalogueScreen(events: List<Hackathon>, savedIds: Set<String>, format: DisplayFormat, query: String, onQuery: (String) -> Unit, filter: Int, onFilter: (Int) -> Unit, scrollState: LazyListState, onOpen: (Hackathon) -> Unit, onBookmark: (Hackathon) -> Unit, onDemo: () -> Unit, onReset: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = scrollState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "heading") {
            Box {
                CircuitDecoration(Modifier.matchParentSize())
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlineIcon(AtlasIcon.ChevronRight, Modifier.size(23.dp), AtlasColors.Cyan)
                            Text(stringResource(Res.string.tech_explore), color = AtlasColors.Cyan, fontSize = 8.sp, lineHeight = 9.sp, letterSpacing = 1.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DemoBadge(onDemo)
                            Text(stringResource(Res.string.tech_ideas), color = AtlasColors.Primary.copy(alpha = .74f), fontSize = 7.sp, lineHeight = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PixelHeading(stringResource(Res.string.hackathons), fontSize = 29.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.home_subtitle), color = AtlasColors.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    SearchField(query, onQuery)
                    Spacer(Modifier.height(7.dp))
                    val labels = listOf(stringResource(Res.string.all), stringResource(Res.string.online), stringResource(Res.string.offline), stringResource(Res.string.open_registration))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        labels.forEachIndexed { index, label -> CatalogueChip(label, filter == index) { onFilter(index) } }
                    }
                }
            }
        }
        if (events.isEmpty()) item { StatePanel(stringResource(Res.string.empty_search), stringResource(Res.string.empty_search_body), stringResource(Res.string.reset_filters), onReset) }
        items(events, key = { it.id }) { event -> HackathonCard(event, event.id in savedIds, format, { onOpen(event) }, { onBookmark(event) }) }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(Res.string.search_label)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(13.dp)).background(AtlasColors.SurfaceRaised).border(1.dp, AtlasColors.Border.copy(alpha = .7f), RoundedCornerShape(13.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlineIcon(AtlasIcon.Search, Modifier.size(19.dp), AtlasColors.TextSecondary)
        Spacer(Modifier.width(9.dp))
        BasicTextField(value, onValue, Modifier.weight(1f).padding(vertical = 13.dp).semantics { contentDescription = label }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = AtlasColors.TextPrimary, fontSize = 12.sp), singleLine = true, cursorBrush = SolidColor(AtlasColors.Primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), decorationBox = { inner ->
            Box { if (value.isEmpty()) Text(stringResource(Res.string.search_hint), fontSize = 11.sp, color = AtlasColors.TextMuted, maxLines = 1); inner() }
        })
        if (value.isNotEmpty()) IconAction(AtlasIcon.Close, stringResource(Res.string.close), { onValue("") }, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun CatalogueChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, Modifier.clip(RoundedCornerShape(13.dp)).background(if (active) AtlasColors.Primary else AtlasColors.Surface).border(1.dp, if (active) AtlasColors.Primary else AtlasColors.Border, RoundedCornerShape(13.dp)).padding(horizontal = 13.dp, vertical = 10.dp), fontSize = 11.sp, color = if (active) AtlasColors.Background else AtlasColors.TextPrimary)
    }
}
