package dev.hackatlas.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.platform.rememberExternalActions
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(appState: AppState, initialRoute: String = "home") {
    HackAtlasTheme {
        Surface(Modifier.fillMaxSize(), color = AtlasColors.Background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val state by appState.state.collectAsState()
                LaunchedEffect(appState) { appState.ownerScope.launch { appState.load() } }
                when (val current = state) {
                    BootstrapState.Loading -> LoadingPanel()
                    BootstrapState.ConfigurationUnavailable -> StatePanel(stringResource(Res.string.release_title), stringResource(Res.string.release_body))
                    BootstrapState.StorageFailed -> StatePanel(stringResource(Res.string.storage_read_error), stringResource(Res.string.storage_read_error_body), stringResource(Res.string.retry), { appState.ownerScope.launch { appState.load() } })
                    is BootstrapState.Ready -> CatalogApp(appState, current, initialRoute)
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CatalogApp(app: AppState, ready: BootstrapState.Ready, initialRoute: String) {
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    var previousRoute by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var selectedDemo by rememberSaveable { mutableStateOf(false) }
    var openLocalCopy by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val bookmarks by ready.bookmarks.state.collectAsState()
    val pendingKeys = bookmarks.pendingKeys
    val snapshots by ready.saved.entries.collectAsState()
    val catalog by ready.catalog.state.collectAsState()
    val details by ready.details.state.collectAsState()
    val savedKeys = remember(snapshots) { snapshots.map { it.key }.toSet() }
    val profile = ready.repository?.profile?.collectAsState()?.value
    val saveableStates = rememberSaveableStateHolder()
    val catalogueScroll = rememberLazyListState()
    val format = displayFormat()
    val externalActions = rememberExternalActions()
    val bundled = app.source == CatalogSource.BundledDemo
    val sourceId = ready.saved.scope.documentScope
    val back: () -> Unit = { route = previousRoute.takeIf { it != "detail" && it != "typography" } ?: "home" }

    val openEvent: (Hackathon) -> Unit = { event ->
        previousRoute = route; selectedId = event.id; selectedDemo = event.isDemo; openLocalCopy = false
        ready.details.open(event.id); route = "detail"
    }
    val openSnapshot: (SavedGuestEvent) -> Unit = { snapshot ->
        previousRoute = route; selectedId = snapshot.key.id; selectedDemo = snapshot.key.isDemo; openLocalCopy = true
        ready.details.showSnapshot(snapshot); route = "detail"
    }
    // Restores navigation after host recreation without refetching an already opened local copy.
    LaunchedEffect(route, selectedId, selectedDemo, openLocalCopy, catalog.items.firstOrNull()?.id) {
        if (route == "detail") {
            if (selectedId.isEmpty()) {
                catalog.items.firstOrNull()?.let { selectedId = it.id; selectedDemo = it.isDemo }
            } else {
                val content = ready.details.state.value as? DetailUiState.Content
                if (openLocalCopy) {
                    if (content?.snapshot?.key != SavedEventKey(selectedId, selectedDemo))
                        snapshots.firstOrNull { it.key == SavedEventKey(selectedId, selectedDemo) }?.let(ready.details::showSnapshot)
                } else if (ready.details.state.value == DetailUiState.Idle) ready.details.open(selectedId)
            }
        }
    }

    fun changeBookmark(event: Hackathon, remove: Boolean, snapshot: SavedGuestEvent? = null) {
        ready.bookmarks.change(event, remove, snapshot)
    }
    val onQuery: (CatalogQuery) -> Unit = {
        ready.catalog.setQuery(it)
        app.ownerScope.launch { catalogueScroll.scrollToItem(0) }
    }
    BackHandler(enabled = route == "detail" || route == "typography", onBack = back)
    val content = details as? DetailUiState.Content
    val screenKey = if (route == "detail") ready.saved.scope.detailStateKey(
        if (!openLocalCopy && content?.detail?.hackathon?.id == selectedId) content.detail.hackathon.savedKey()
        else SavedEventKey(selectedId, selectedDemo), openLocalCopy) else "$sourceId:$route"

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            saveableStates.SaveableStateProvider(screenKey) {
                when (route) {
                    "detail" -> {
                        val matches = content?.detail?.hackathon?.id == selectedId &&
                            if (openLocalCopy) content.snapshot?.key == SavedEventKey(selectedId, selectedDemo) else content.snapshot == null
                        if (matches) {
                            val event = content.detail.hackathon
                            DetailScreen(content.detail, event.savedKey() in savedKeys, format, back,
                                { changeBookmark(event, event.savedKey() in savedKeys, content.snapshot) },
                                externalActions,
                                content.snapshot?.savedAt, bundled, event.savedKey() in pendingKeys)
                        } else Column {
                            IconAction(AtlasIcon.Back, stringResource(Res.string.back), back)
                            when {
                                details is DetailUiState.Error && !openLocalCopy -> StatePanel(stringResource(Res.string.detail_unavailable),
                                    catalogErrorText((details as DetailUiState.Error).error), stringResource(Res.string.retry), ready.details::retry)
                                openLocalCopy -> StatePanel(stringResource(Res.string.detail_unavailable), stringResource(Res.string.snapshot_missing))
                                else -> LoadingPanel()
                            }
                        }
                    }
                    "profile" -> if (profile != null) ProfileScreen(profile.copy(savedCount = snapshots.size),
                        snapshots.map { it.detail.hackathon }, format,
                        { event -> snapshots.firstOrNull { it.key == event.savedKey() }?.let(openSnapshot) },
                        { changeBookmark(it, true) }, { route = "saved" }, { dialog = "settings" }, { dialog = "feature" }, { dialog = "about" })
                    else GuestProfileScreen(snapshots, format, openSnapshot, { changeBookmark(it.detail.hackathon, true) },
                        { route = "saved" }, { dialog = "settings" })
                    "saved" -> SavedScreen(snapshots, format, openSnapshot, { changeBookmark(it.detail.hackathon, true) },
                        { route = "home" }, { dialog = "about" }, bundled)
                    "calendar" -> CalendarScreen(snapshots, format, openSnapshot, { route = "home" })
                    "typography" -> TypographyScreen(back)
                    else -> CatalogueScreen(catalog, savedKeys, pendingKeys, format, onQuery, catalogueScroll,
                        openEvent, { changeBookmark(it, it.savedKey() in savedKeys) }, { dialog = "about" },
                        ready.catalog::retry, ready.catalog::retryMetadata, ready.catalog::loadNext, bundled)
                }
            }
        }
        if (route != "detail" && route != "typography") AtlasTabBar(route) { route = it }
    }
    val bookmarkFailure = bookmarks.failures.firstOrNull()
    if (bookmarkFailure != null) {
        val acknowledge = { ready.bookmarks.acknowledgeFailure(bookmarkFailure.id) }
        val body = when (val error = bookmarkFailure.error) {
            BookmarkError.Storage -> stringResource(Res.string.storage_write_error_body)
            is BookmarkError.Catalog -> catalogErrorText(error.error)
        }
        AlertDialog(onDismissRequest = acknowledge, title = { Text(stringResource(Res.string.storage_write_error)) },
            text = { Text(body, color = AtlasColors.TextSecondary) }, containerColor = AtlasColors.SurfaceRaised,
            titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = acknowledge) { Text(stringResource(Res.string.understood), color = AtlasColors.Primary) } })
    } else if (dialog != null) {
        val title = stringResource(when (dialog) {
            "settings" -> Res.string.settings
            "about" -> if (bundled) Res.string.demo_dialog_title else Res.string.server_demo_title
            else -> Res.string.feature_pending_title
        })
        val body = stringResource(when (dialog) {
                "about" -> if (bundled) Res.string.demo_dialog_body else Res.string.server_demo_body
                else -> Res.string.demo_feature_body
            })
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text(title) }, text = { Text(body, color = AtlasColors.TextSecondary) },
            containerColor = AtlasColors.SurfaceRaised, titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.understood), color = AtlasColors.Primary) } },
            dismissButton = if (dialog == "settings") ({ TextButton(onClick = { previousRoute = route; route = "typography"; dialog = null }) { Text(stringResource(Res.string.typography), color = AtlasColors.Cyan) } }) else null)
    }
}

@Composable
private fun displayFormat() = DisplayFormat(
    stringArrayResource(Res.array.short_months), stringResource(Res.string.date_unknown), stringResource(Res.string.not_specified),
    stringResource(Res.string.prize_unknown), stringResource(Res.string.no_prize), stringResource(Res.string.non_cash_prize),
    stringResource(Res.string.online), stringResource(Res.string.offline), stringResource(Res.string.hybrid),
)

@Composable
private fun AtlasTabBar(route: String, onRoute: (String) -> Unit) {
    val tabs = listOf("home" to AtlasIcon.Home, "calendar" to AtlasIcon.Calendar, "saved" to AtlasIcon.Bookmark, "profile" to AtlasIcon.User)
    val labels = listOf(stringResource(Res.string.home), stringResource(Res.string.calendar), stringResource(Res.string.saved), stringResource(Res.string.profile))
    Column {
        HorizontalDivider(color = AtlasColors.Border.copy(alpha = .48f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth().background(AtlasColors.Background).heightIn(min = 65.dp)) {
            tabs.forEachIndexed { index, (key, icon) ->
                val active = route == key
                val color = if (active) AtlasColors.Primary else AtlasColors.TextMuted
                Column(Modifier.weight(1f).clickable(role = Role.Tab, onClick = { onRoute(key) }).semantics { selected = active }.padding(vertical = 10.dp, horizontal = 1.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlineIcon(icon, Modifier.size(23.dp), color)
                    Text(labels[index], fontSize = 10.sp, color = color, lineHeight = 12.sp)
                }
            }
        }
    }
}
