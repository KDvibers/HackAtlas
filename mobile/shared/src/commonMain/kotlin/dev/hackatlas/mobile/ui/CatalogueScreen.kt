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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun CatalogueScreen(state: CatalogUiState, savedKeys: Set<SavedEventKey>, pendingKeys: Set<SavedEventKey>,
    format: DisplayFormat, submitQuery: (CatalogQuery) -> Unit, scrollState: LazyListState,
    onOpen: (Hackathon) -> Unit, onBookmark: (Hackathon) -> Unit, onDemo: () -> Unit,
    onRetry: () -> Unit, onRetryMetadata: () -> Unit, onNext: () -> Unit, bundled: Boolean) {
    var filtersOpen by remember { mutableStateOf(false) }
    // The editor owns text/selection synchronously; the store owns requests.
    // Re-entering this screen takes its query from the process-owned store,
    // rather than restoring text independently from the active filters/results.
    val search = remember { TextFieldState(state.query.query) }
    var filters by remember { mutableStateOf(state.query) }
    val sendQuery by rememberUpdatedState(submitQuery)
    val query = filters.copy(query = search.text.toString())
    LaunchedEffect(search) {
        snapshotFlow { search.text.toString() }.collect { text ->
            // Initial collection/navigation must not resubmit an unchanged
            // query: the host scrolls to the top only for a user's new query.
            if (text != filters.query) {
                filters = filters.copy(query = text)
                sendQuery(filters)
            }
        }
    }
    val onQuery: (CatalogQuery) -> Unit = { next ->
        filters = next
        if (search.text.toString() != next.query) search.setTextAndPlaceCursorAtEnd(next.query)
        submitQuery(next)
    }
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
                            if (bundled) DemoBadge(onDemo)
                            Text(stringResource(Res.string.tech_ideas), color = AtlasColors.Primary.copy(alpha = .74f), fontSize = 7.sp, lineHeight = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PixelHeading(stringResource(Res.string.hackathons), fontSize = 29.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.home_subtitle), color = AtlasColors.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    SearchField(search)
                    Spacer(Modifier.height(7.dp))
                    val labels = listOf(Res.string.all, Res.string.online, Res.string.offline, Res.string.open_registration, Res.string.kazakhstan)
                    val active = listOf(query == CatalogQuery(query = query.query, sort = query.sort, size = query.size),
                        query.format == EventFormat.ONLINE, query.format == EventFormat.OFFLINE,
                        query.registrationStatus == RegistrationStatus.OPEN, query.countryCode == "KZ")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        QuickFilter.entries.forEachIndexed { index, filter ->
                            CatalogueChip(stringResource(labels[index]), active[index]) { onQuery(query.toggleQuickFilter(filter)) }
                        }
                        CatalogueChip(stringResource(Res.string.filters_sort), query.topic != null || query.city != null || query.sort != CatalogSort.DEADLINE_ASC) { filtersOpen = true }
                    }
                }
            }
        }
        when {
            state.loading -> item(key = "loading") { LoadingPanel() }
            state.error != null -> item(key = "error") {
                StatePanel(stringResource(Res.string.catalog_unavailable), catalogErrorText(state.error), stringResource(Res.string.retry), onRetry)
            }
            state.isEmpty -> item(key = "empty") {
                StatePanel(stringResource(Res.string.empty_search), stringResource(Res.string.empty_search_body),
                    stringResource(Res.string.reset_filters), { onQuery(CatalogQuery()) })
            }
        }
        items(state.items, key = { it.savedKey().listKey() }) { event ->
            HackathonCard(event, event.savedKey() in savedKeys, format, { onOpen(event) }, { onBookmark(event) },
                bookmarkEnabled = event.savedKey() !in pendingKeys)
        }
        if (state.loadingMore) item(key = "loading-more") { LoadingPanel() }
        else if (state.appendError != null) item(key = "append-error") {
            StatePanel(stringResource(Res.string.page_unavailable), catalogErrorText(state.appendError), stringResource(Res.string.retry), onRetry)
        } else if (!state.loading && state.error == null && state.nextPage != null) item(key = "next") {
            NeonButton(stringResource(Res.string.load_more), onNext, Modifier.fillMaxWidth())
        }
    }
    if (filtersOpen) CatalogFiltersSheet(state.copy(query = query), onQuery, onRetryMetadata) { filtersOpen = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CatalogFiltersSheet(state: CatalogUiState, onQuery: (CatalogQuery) -> Unit, onRetryMetadata: () -> Unit, onClose: () -> Unit) {
    val query = state.query
    ModalBottomSheet(onDismissRequest = onClose, containerColor = AtlasColors.SurfaceRaised, contentColor = AtlasColors.TextPrimary,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(Res.string.filters_sort))
            Text(stringResource(Res.string.filter_and_note), color = AtlasColors.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            SectionTitle(stringResource(Res.string.sort))
            val sorts = listOf(Res.string.sort_deadline, Res.string.sort_start, Res.string.sort_newest)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CatalogSort.entries.forEachIndexed { index, value ->
                    CatalogueChip(stringResource(sorts[index]), query.sort == value) { onQuery(query.copy(sort = value)) }
                }
            }
            CatalogueChip(stringResource(Res.string.individual_application), query.individualApplicationAllowed == true) {
                onQuery(query.copy(individualApplicationAllowed = if (query.individualApplicationAllowed == true) null else true))
            }
            if (state.metadataLoading) LoadingPanel()
            else if (state.metadataError != null) StatePanel(stringResource(Res.string.filters_unavailable),
                catalogErrorText(state.metadataError), stringResource(Res.string.retry), onRetryMetadata)
            state.metadata?.let { metadata ->
                SectionTitle(stringResource(Res.string.format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CatalogueChip(stringResource(Res.string.all), query.format == null) { onQuery(query.copy(format = null)) }
                    metadata.formats.forEach { tag ->
                        EventFormat.entries.firstOrNull { it.name == tag.id && it != EventFormat.UNSUPPORTED }?.let { value ->
                            CatalogueChip(tag.label, query.format == value) { onQuery(query.copy(format = value)) }
                        }
                    }
                }
                SectionTitle(stringResource(Res.string.country))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CatalogueChip(stringResource(Res.string.all), query.countryCode == null) { onQuery(query.copy(countryCode = null, city = null)) }
                    metadata.countries.forEach { country ->
                        CatalogueChip(country.label, query.countryCode == country.code) { onQuery(query.copy(countryCode = country.code, city = null)) }
                    }
                }
                val cities = metadata.cities.filter { it.countryCode == query.countryCode }
                if (cities.isNotEmpty()) {
                    SectionTitle(stringResource(Res.string.city))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        CatalogueChip(stringResource(Res.string.all), query.city == null) { onQuery(query.copy(city = null)) }
                        cities.forEach { city -> CatalogueChip(city.name, query.city == city.name) { onQuery(query.copy(city = city.name)) } }
                    }
                }
                SectionTitle(stringResource(Res.string.topics))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    CatalogueChip(stringResource(Res.string.all), query.topic == null) { onQuery(query.copy(topic = null)) }
                    metadata.topics.forEach { tag -> CatalogueChip(tag.label, query.topic == tag.id) { onQuery(query.copy(topic = tag.id)) } }
                }
            }
            TextButton(onClick = { onQuery(CatalogQuery(query = query.query)) }) { Text(stringResource(Res.string.reset_filters), color = AtlasColors.Cyan) }
            NeonButton(stringResource(Res.string.show_results), onClose, Modifier.fillMaxWidth())
        }
    }
}
@Composable
private fun SearchField(state: TextFieldState) {
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(Res.string.search_label)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(13.dp)).background(AtlasColors.SurfaceRaised).border(1.dp, AtlasColors.Border.copy(alpha = .7f), RoundedCornerShape(13.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlineIcon(AtlasIcon.Search, Modifier.size(19.dp), AtlasColors.TextSecondary)
        Spacer(Modifier.width(9.dp))
        BasicTextField(state, Modifier.weight(1f).padding(vertical = 13.dp).semantics { contentDescription = label }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = AtlasColors.TextPrimary, fontSize = 12.sp), lineLimits = TextFieldLineLimits.SingleLine, inputTransformation = InputTransformation.maxLength(120), cursorBrush = SolidColor(AtlasColors.Primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), onKeyboardAction = { keyboard?.hide() }, decorator = { inner ->
            Box { if (state.text.isEmpty()) Text(stringResource(Res.string.search_hint), fontSize = 11.sp, color = AtlasColors.TextMuted, maxLines = 1); inner() }
        })
        if (state.text.isNotEmpty()) IconAction(AtlasIcon.Close, stringResource(Res.string.close), { state.setTextAndPlaceCursorAtEnd("") }, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun CatalogueChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Checkbox, onClick = onClick).semantics { selected = active }, contentAlignment = Alignment.Center) {
        Text(text, Modifier.clip(RoundedCornerShape(13.dp)).background(if (active) AtlasColors.Primary else AtlasColors.Surface).border(1.dp, if (active) AtlasColors.Primary else AtlasColors.Border, RoundedCornerShape(13.dp)).padding(horizontal = 13.dp, vertical = 10.dp), fontSize = 11.sp, color = if (active) AtlasColors.Background else AtlasColors.TextPrimary)
    }
}
