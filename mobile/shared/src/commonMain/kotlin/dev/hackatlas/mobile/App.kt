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
import dev.hackatlas.mobile.data.DemoRepository
import dev.hackatlas.mobile.data.GuestSavedRepository
import dev.hackatlas.mobile.data.GuestStorageException
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.resources.*
import dev.hackatlas.mobile.ui.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun App(appState: AppState, initialRoute: String = "home", demoEnabled: Boolean = true) {
    HackAtlasTheme {
        Surface(Modifier.fillMaxSize(), color = AtlasColors.Background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (!demoEnabled) {
                    StatePanel(stringResource(Res.string.release_title), stringResource(Res.string.release_body))
                } else {
                    val state by appState.state.collectAsState()
                    val scope = rememberCoroutineScope()
                    LaunchedEffect(appState) { appState.load() }
                    when (val current = state) {
                        BootstrapState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                CircularProgressIndicator(color = AtlasColors.Primary, strokeWidth = 2.dp)
                                Text(stringResource(Res.string.loading), color = AtlasColors.TextSecondary)
                            }
                        }
                        BootstrapState.StorageFailed -> StatePanel(stringResource(Res.string.storage_read_error), stringResource(Res.string.storage_read_error_body), stringResource(Res.string.retry), { scope.launch { appState.load() } })
                        is BootstrapState.Ready -> DemoApp(current.repository, current.saved, initialRoute)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DemoApp(repository: DemoRepository?, savedRepository: GuestSavedRepository, initialRoute: String) {
    var route by rememberSaveable { mutableStateOf(initialRoute) }
    var previousRoute by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf(repository?.catalog(CatalogQuery(sort = CatalogSort.NEWEST))?.items?.firstOrNull()?.id.orEmpty()) }
    var openLocalCopy by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val snapshots by savedRepository.entries.collectAsState()
    val savedIds = remember(snapshots) { snapshots.map { it.detail.hackathon.id }.toSet() }
    val savedHackathons = remember(snapshots) { snapshots.map { it.detail.hackathon } }
    val profile = repository?.profile?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    val saveableStates = rememberSaveableStateHolder()
    val catalogueScroll = rememberLazyListState()
    val format = displayFormat()
    val openEvent: (String, Boolean) -> Unit = { id, local -> previousRoute = route; selectedId = id; openLocalCopy = local; route = "detail" }
    val toggleSaved: (String) -> Unit = { id ->
        val detail = snapshots.firstOrNull { it.detail.hackathon.id == id }?.detail ?: repository?.detail(id)
        val remove = id in savedIds
        if (detail != null) scope.launch {
            try { if (remove) savedRepository.remove(id) else savedRepository.save(detail) }
            catch (_: GuestStorageException) { dialog = "storage" }
        }
    }
    val removeSaved: (String) -> Unit = { id ->
        scope.launch {
            try { savedRepository.remove(id) }
            catch (_: GuestStorageException) { dialog = "storage" }
        }
    }
    val back: () -> Unit = { route = previousRoute.takeIf { it != "detail" && it != "typography" } ?: "home" }
    BackHandler(enabled = route == "detail" || route == "typography", onBack = back)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            saveableStates.SaveableStateProvider(route) {
            when (route) {
                "detail" -> {
                    // Keep the opened copy visible when its bookmark is removed while reading.
                    val openedSnapshot = remember(selectedId, openLocalCopy) { snapshots.firstOrNull { it.detail.hackathon.id == selectedId } }
                    val detail = if (openLocalCopy) openedSnapshot?.detail else repository?.detail(selectedId)
                    if (detail != null) DetailScreen(detail, selectedId in savedIds, format, back, {
                        val id = detail.hackathon.id
                        val remove = id in savedIds
                        scope.launch {
                            try { if (remove) savedRepository.remove(id) else savedRepository.save(detail) }
                            catch (_: GuestStorageException) { dialog = "storage" }
                        }
                    }, { dialog = "feature" }, { dialog = "register" }, if (openLocalCopy) openedSnapshot?.savedAt else null)
                    else StatePanel(stringResource(Res.string.detail_unavailable), stringResource(Res.string.catalog_unavailable_body), stringResource(Res.string.back), back)
                }
                "profile" -> if (profile != null) ProfileScreen(profile.copy(savedCount = snapshots.size), savedHackathons, format, { openEvent(it.id, true) }, { removeSaved(it.id) }, { route = "saved" }, { dialog = "settings" }, { dialog = "feature" }, { dialog = "about" })
                    else StatePanel(stringResource(Res.string.guest_profile), stringResource(Res.string.guest_profile_body), stringResource(Res.string.saved), { route = "saved" })
                "saved" -> SavedScreen(snapshots, format, { openEvent(it.id, true) }, { removeSaved(it.id) }, { route = "home" }, { dialog = "about" })
                "calendar" -> CalendarScreen(snapshots, format, { openEvent(it, true) }, { route = "home" })
                "typography" -> TypographyScreen(back)
                else -> if (repository != null) CatalogueScreen(
                    events = repository.catalog(CatalogQuery(query = query, format = when (filter) { 1 -> EventFormat.ONLINE; 2 -> EventFormat.OFFLINE; else -> null }, registrationStatus = if (filter == 3) RegistrationStatus.OPEN else null, sort = CatalogSort.NEWEST)).items,
                    savedIds = savedIds, format = format, query = query, onQuery = { query = it.take(120) }, filter = filter, onFilter = { filter = it }, scrollState = catalogueScroll,
                    onOpen = { openEvent(it.id, false) }, onBookmark = { toggleSaved(it.id) }, onDemo = { dialog = "about" }, onReset = { query = ""; filter = 0 },
                )
                else StatePanel(stringResource(Res.string.catalog_unavailable), stringResource(Res.string.catalog_unavailable_body), stringResource(Res.string.saved), { route = "saved" })
            }
            }
        }
        if (route != "detail" && route != "typography") AtlasTabBar(route) { route = it }
    }

    if (dialog != null) {
        val title = stringResource(when (dialog) { "settings" -> Res.string.settings; "storage" -> Res.string.storage_write_error; else -> Res.string.demo_dialog_title })
        val body = stringResource(when (dialog) { "register" -> Res.string.demo_register_body; "feature" -> Res.string.demo_feature_body; "storage" -> Res.string.storage_write_error_body; else -> Res.string.demo_dialog_body })
        AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(title) }, text = { Text(body, color = AtlasColors.TextSecondary) },
            containerColor = AtlasColors.SurfaceRaised, titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.understood), color = AtlasColors.Primary) } },
            dismissButton = if (dialog == "settings") ({ TextButton(onClick = { previousRoute = route; route = "typography"; dialog = null }) { Text(stringResource(Res.string.typography), color = AtlasColors.Cyan) } }) else null,
        )
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
