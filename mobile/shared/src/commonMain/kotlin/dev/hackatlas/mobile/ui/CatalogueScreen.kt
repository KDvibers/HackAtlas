package dev.hackatlas.mobile.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    LazyColumn(
        Modifier.fillMaxSize().background(CatalogueColors.Background),
        state = scrollState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "heading") {
            Column {
                HackAtlasBrand(bundled, onDemo)
                Spacer(Modifier.height(7.dp))
                Text(
                    stringResource(Res.string.hackathons),
                    Modifier.semantics { heading() },
                    color = CatalogueColors.Text,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 30.sp, lineHeight = 41.sp),
                )
                Text(
                    stringResource(Res.string.home_subtitle),
                    color = CatalogueColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                SearchField(search)
                Spacer(Modifier.height(6.dp))
                val labels = listOf(Res.string.all, Res.string.online, Res.string.offline, Res.string.open_registration, Res.string.kazakhstan)
                val active = listOf(query == CatalogQuery(query = query.query, sort = query.sort, size = query.size),
                    query.format == EventFormat.ONLINE, query.format == EventFormat.OFFLINE,
                    query.registrationStatus == RegistrationStatus.OPEN, query.countryCode == "KZ")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    QuickFilter.entries.forEachIndexed { index, filter ->
                        CatalogueChip(stringResource(labels[index]), active[index], HeaderChips) { onQuery(query.toggleQuickFilter(filter)) }
                    }
                    CatalogueChip(stringResource(Res.string.filters_sort), query.topic != null || query.city != null || query.sort != CatalogSort.DEADLINE_ASC, HeaderChips) {
                        filtersOpen = true
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
            CatalogueCard(event, event.savedKey() in savedKeys, format, { onOpen(event) }, { onBookmark(event) },
                bookmarkPending = event.savedKey() in pendingKeys)
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
internal fun HackAtlasBrand(showDemo: Boolean, onDemo: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Canvas(Modifier.size(28.dp)) {
                val x = size.width
                val y = size.height
                val strokeWidth = x * .22f
                val left = x * .16f
                val right = x * .84f
                val top = y * .17f
                val bottom = y * .83f
                drawLine(CatalogueColors.Lime, Offset(left, top), Offset(left, bottom), strokeWidth, StrokeCap.Round)
                drawLine(CatalogueColors.Lime, Offset(right, top), Offset(right, bottom), strokeWidth, StrokeCap.Round)
                val step = Path().apply {
                    moveTo(left, y * .57f)
                    lineTo(x * .45f, y * .57f)
                    lineTo(x * .55f, y * .43f)
                    lineTo(right, y * .43f)
                }
                drawPath(step, CatalogueColors.Lime, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Text(
                stringResource(Res.string.app_name), color = CatalogueColors.Text,
                style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (showDemo) {
            val badgeShape = RoundedCornerShape(24.dp)
            val badge = Modifier.heightIn(min = 48.dp).clip(badgeShape)
            val demoBadge = if (onDemo == null) badge else badge.clickable(role = Role.Button, onClick = onDemo)
            Box(
                demoBadge,
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.demo_label),
                    Modifier.border(1.dp, CatalogueColors.Border, badgeShape).padding(horizontal = 12.dp, vertical = 5.dp),
                    color = CatalogueColors.Text, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SearchField(state: TextFieldState) {
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(Res.string.search_label)
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(CatalogueColors.Surface)
            .border(1.dp, CatalogueColors.Border, shape).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlineIcon(AtlasIcon.Search, Modifier.size(19.dp), CatalogueColors.Muted)
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            state,
            Modifier.weight(1f).padding(vertical = 12.dp).semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = CatalogueColors.Text, fontSize = 13.sp),
            lineLimits = TextFieldLineLimits.SingleLine,
            inputTransformation = InputTransformation.maxLength(120),
            cursorBrush = SolidColor(CatalogueColors.Lime),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { keyboard?.hide() },
            decorator = { inner ->
                Box {
                    if (state.text.isEmpty()) {
                        Text(
                            stringResource(Res.string.search_hint),
                            color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (state.text.isNotEmpty()) {
            IconAction(
                AtlasIcon.Close, stringResource(Res.string.close), { state.setTextAndPlaceCursorAtEnd("") },
                tint = CatalogueColors.Muted, modifier = Modifier.size(40.dp),
            )
        }
    }
}

private data class CatalogueChipStyle(
    val surface: Color,
    val active: Color,
    val border: Color,
    val radius: Dp,
)

private val HeaderChips = CatalogueChipStyle(CatalogueColors.Surface, CatalogueColors.Lime, CatalogueColors.Border, 24.dp)
private val SheetChips = CatalogueChipStyle(AtlasColors.Surface, AtlasColors.Primary, AtlasColors.Border, 13.dp)

@Composable
private fun CatalogueChip(text: String, active: Boolean, style: CatalogueChipStyle = SheetChips, onClick: () -> Unit) {
    val shape = RoundedCornerShape(style.radius)
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Checkbox, onClick = onClick).semantics { selected = active },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.clip(shape).background(if (active) style.active else style.surface)
                .border(1.dp, if (active) style.active else style.border, shape)
                .padding(horizontal = 13.dp, vertical = 10.dp),
            fontSize = 11.sp, color = if (active) AtlasColors.Background else AtlasColors.TextPrimary,
        )
    }
}
