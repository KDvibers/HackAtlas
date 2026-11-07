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
import dev.hackatlas.mobile.ui.auth.*
import dev.hackatlas.mobile.ui.saved.*
import dev.hackatlas.mobile.ui.profile.*
import dev.hackatlas.mobile.ui.applications.*
import dev.hackatlas.mobile.ui.auth.AuthOperation
import dev.hackatlas.mobile.data.auth.AuthState
import dev.hackatlas.mobile.data.auth.LocalSessionIdentity
import dev.hackatlas.mobile.data.auth.PrivateAccess
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
    var applicationReturnId by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val bookmarks by ready.bookmarks.state.collectAsState()
    val guestSnapshots by ready.saved.entries.collectAsState()
    val catalog by ready.catalog.state.collectAsState()
    val details by ready.details.state.collectAsState()
    val profile = ready.repository?.profile?.collectAsState()?.value
    val auth = app.auth
    val authState = auth?.state?.collectAsState()?.value
    val account = (authState?.session as? AuthState.Authenticated)?.user
    val editor=app.profileEditor
    val editState=editor?.state?.collectAsState()?.value?.let { editor.visibleFor(it, authState) }
    val editRoute=route=="editProfile"
    val accountSaved = ready.accountSaved
    val savedState = accountSaved?.state?.collectAsState()?.value?.visibleFor(authState)
    val n2 = authState?.privateAccess != null
    val accountMode = accountSaved != null && if (n2) authState?.canReadPrivate == true else
        (savedState?.mode != SavedMode.GUEST || authState?.localIdentity != null || authState?.busy == AuthOperation.LOGOUT)
    val identity = authState?.localIdentity
    val applications = ready.applications
    val applicationState = applications?.state?.collectAsState()?.value?.visibleFor(authState)
    val cacheVisible = savedState?.mode == SavedMode.ACCOUNT && savedState.ownerId == identity?.userId &&
        authState?.canReadPrivate == true
    val snapshots = if(accountMode) savedState?.entries?.takeIf { cacheVisible }.orEmpty() else guestSnapshots
    val activeOwner = if(accountMode) identity?.generation ?: identity?.userId ?: "pending" else "guest"
    val canWrite = !accountMode || (cacheVisible && savedState?.writable == true && !savedState.syncing)
    val savedKeys = remember(snapshots) { snapshots.map { it.key }.toSet() }
    val pendingKeys = if(!canWrite) (catalog.items.map { it.savedKey() } + snapshots.map { it.key } +
        listOfNotNull((details as? DetailUiState.Content)?.detail?.hackathon?.savedKey())).toSet()
        else if(accountMode) savedState?.pendingKeys.orEmpty() else bookmarks.pendingKeys
    var selectedSavedOwner by remember { mutableStateOf<String?>(null) }
    var importCandidates by remember { mutableStateOf<List<SavedGuestEvent>?>(null) }
    var importOwner by remember { mutableStateOf<LocalSessionIdentity?>(null) }
    var importAccess by remember { mutableStateOf<PrivateAccess?>(null) }
    val authRoute = route == "login" || route == "register"
    val saveableStates = rememberSaveableStateHolder()
    val privateStateKeys = remember { mutableSetOf<String>() }
    var previousOwner by remember { mutableStateOf(activeOwner) }
    val catalogueScroll = rememberLazyListState()
    val format = displayFormat()
    val externalActions = rememberExternalActions()
    val bundled = app.source == CatalogSource.BundledDemo
    val sourceId = ready.saved.scope.documentScope
    val back: () -> Unit = { route = previousRoute.takeIf { it != "detail" && it != "typography" } ?: "home" }
    val closeAuth: () -> Unit = {
        route = if(applicationReturnId != null) "detail" else "profile"
        applicationReturnId = null
    }
    val closeEditor:()->Unit={
        if(editState?.dirty==true && !editState.busy)dialog="discardProfile"
        else{editor?.close();route="profile"}
    }
    LaunchedEffect(editState?.saved,editState?.draft,route) {
        if(editRoute && (editState?.saved==true || editState?.draft==null)){
            if(dialog=="discardProfile") dialog=null
            editor?.close();route="profile"
        }
    }
    val savedStatus: @Composable () -> Unit = {
        if(accountSaved != null && savedState != null) AccountSavedStatus(accountSaved, savedState, guestSnapshots, format,
            onImport = {
                importCandidates = guestSnapshots.toList()
                importOwner = identity
                importAccess = authState?.privateAccess
            })
    }
    LaunchedEffect(authState?.privateAccess) {
        if (n2 && (authState?.canWritePrivate != true || importAccess != authState.privateAccess))
            importCandidates = null
    }
    LaunchedEffect(activeOwner) {
        if(previousOwner != activeOwner) {
            privateStateKeys.forEach(saveableStates::removeState); privateStateKeys.clear()
            if(openLocalCopy && selectedSavedOwner != activeOwner) {
                route = "home"; selectedId = ""; selectedSavedOwner = null; openLocalCopy = false
            }
            if(importOwner != identity) importCandidates = null
            previousOwner = activeOwner
        }
    }
    LaunchedEffect(route) { if (route == "profile") auth?.refresh() }
    LaunchedEffect(authState?.session) {
        if (authRoute && authState?.session is AuthState.Authenticated) {
            val returnId = applicationReturnId;applicationReturnId = null
            if(returnId != null) {
                selectedId = returnId;openLocalCopy = false;selectedSavedOwner = null
                ready.details.open(returnId);route = "detail"
            } else route = "profile"
        }
    }

    val openEvent: (Hackathon) -> Unit = { event ->
        previousRoute = route; selectedId = event.id; selectedDemo = event.isDemo; openLocalCopy = false
        ready.details.open(event.id); route = "detail"
    }
    val openSnapshot: (SavedGuestEvent) -> Unit = { snapshot ->
        selectedSavedOwner = activeOwner
        previousRoute = route; selectedId = snapshot.key.id; selectedDemo = snapshot.key.isDemo; openLocalCopy = true
        ready.details.showSnapshot(snapshot); route = "detail"
    }
    // Restores navigation after host recreation without refetching an already opened local copy.
    LaunchedEffect(route, selectedId, selectedDemo, openLocalCopy, catalog.items.firstOrNull()?.id, activeOwner, snapshots, savedState?.cachedAt, cacheVisible) {
        if (route == "detail") {
            if (selectedId.isEmpty()) {
                catalog.items.firstOrNull()?.let { selectedId = it.id; selectedDemo = it.isDemo }
            } else {
                val content = ready.details.state.value as? DetailUiState.Content
                if (openLocalCopy) {
                    val currentSnapshot = snapshots.firstOrNull { it.key == SavedEventKey(selectedId, selectedDemo) }
                    if(accountMode && cacheVisible && savedState?.cachedAt != null && currentSnapshot == null) {
                        openLocalCopy = false
                        if(content?.snapshot != null) ready.details.reconcileAccountSnapshots(snapshots)
                        else ready.details.open(selectedId)
                    } else currentSnapshot?.takeIf { it != content?.snapshot }?.let {
                            selectedSavedOwner = activeOwner; ready.details.showSnapshot(it)
                        }
                } else if (ready.details.state.value == DetailUiState.Idle) ready.details.open(selectedId)
            }
        }
    }

    fun changeBookmark(event: Hackathon, remove: Boolean, snapshot: SavedGuestEvent? = null) {
        if(accountMode) accountSaved?.change(event, remove)
        else ready.bookmarks.change(event, remove, snapshot)
    }
    val onQuery: (CatalogQuery) -> Unit = {
        ready.catalog.setQuery(it)
        app.ownerScope.launch { catalogueScroll.scrollToItem(0) }
    }
    BackHandler(enabled = route == "detail" || route == "typography" || authRoute || editRoute,
        onBack = if(editRoute) closeEditor else if (authRoute) closeAuth else back)
    val content = details as? DetailUiState.Content
    val screenKey = "$activeOwner:" + if (route == "detail") ready.saved.scope.detailStateKey(
        if (!openLocalCopy && content?.detail?.hackathon?.id == selectedId) content.detail.hackathon.savedKey()
        else SavedEventKey(selectedId, selectedDemo), openLocalCopy) else "$sourceId:$route"
    if(activeOwner != "guest") privateStateKeys.add(screenKey)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            saveableStates.SaveableStateProvider(screenKey) {
                when (route) {
                    "editProfile" -> if(editor!=null && editState?.draft != null)ProfileEditScreen(editor,catalog.metadata,catalog.metadataLoading,
                        ready.catalog::retryMetadata,closeEditor)
                    "login", "register" -> if (auth != null) AuthScreen(auth,
                        if (route == "register") AuthFormMode.REGISTER else AuthFormMode.LOGIN,
                        { route = if (it == AuthFormMode.REGISTER) "register" else "login" }, closeAuth)
                    else GuestProfileScreen(snapshots, format, openSnapshot, { changeBookmark(it.detail.hackathon, true) },
                        { route = "saved" }, { dialog = "settings" })
                    "detail" -> {
                        val present = !accountMode || !openLocalCopy || savedState?.cachedAt == null || snapshots.any { it.key == SavedEventKey(selectedId, selectedDemo) }
                        val matches = present && (!openLocalCopy || !accountMode || cacheVisible) && (!openLocalCopy || selectedSavedOwner == activeOwner) && content?.detail?.hackathon?.id == selectedId &&
                            if (openLocalCopy) content.snapshot?.key == SavedEventKey(selectedId, selectedDemo) else content.snapshot == null
                        if (matches) {
                            val event = content.detail.hackathon
                            DetailScreen(content.detail, event.savedKey() in savedKeys, format, back,
                                { changeBookmark(event, event.savedKey() in savedKeys, content.snapshot) },
                                externalActions,
                                content.snapshot?.savedAt, bundled, event.savedKey() in pendingKeys,
                                applications = applicationState,
                                onApplicationsRefresh = { applications?.refresh() },
                                onApplicationsRecover = { applications?.retryCleanup();auth?.refresh() },
                                onApplicationChange = { remove -> applications?.change(event.id,remove) },
                                onApplicationSignIn = if(auth != null && (!n2 || identity == null))
                                    ({ applicationReturnId = event.id;route = "login" }) else null)
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
                    "profile" -> if (account != null) AccountProfileScreen(
                        account, authState, snapshots, format, openSnapshot,
                        { changeBookmark(it.detail.hackathon, true) }, { route = "saved" }, { dialog = "settings" },
                        auth::refresh, { dialog = "logout" }, accountSaved = accountMode,
                        savedRemoveEnabled = canWrite, onEdit=if(editor!=null && (!n2 || authState.canWritePrivate))
                            ({if(editor.open())route="editProfile"})else null,
                        savedStatus = savedStatus)
                    else if(authState?.localIdentity != null) UnverifiedAccountProfile(auth, authState,
                        { dialog = "logout" }, savedStatus)
                    else if (profile != null) ProfileScreen(profile.copy(savedCount = snapshots.size),
                        snapshots.map { it.detail.hackathon }, format,
                        { event -> snapshots.firstOrNull { it.key == event.savedKey() }?.let(openSnapshot) },
                        { changeBookmark(it, true) }, { route = "saved" }, { dialog = "settings" }, { dialog = "feature" }, { dialog = "about" })
                    else GuestProfileScreen(snapshots, format, openSnapshot, { changeBookmark(it.detail.hackathon, true) },
                        { route = "saved" }, { dialog = "settings" }, authActions = {
                            if (auth != null && authState != null) GuestAuthActions(auth, authState,
                                { auth.dismissFeedback(); route = "login" },
                                { auth.dismissFeedback(); route = "register" }, { dialog = "logout" })
                        })
                    "saved" -> SavedScreen(snapshots, format, openSnapshot, { changeBookmark(it.detail.hackathon, true) },
                        { route = "home" }, { dialog = "about" }, bundled, accountMode = accountMode,
                        showEmpty = !accountMode || (cacheVisible && savedState?.cachedAt != null && !savedState.syncing),
                        removeEnabled = canWrite, statusContent = savedStatus)
                    "calendar" -> CalendarScreen(snapshots, format, openSnapshot, { route = "home" }, savedStatus)
                    "typography" -> TypographyScreen(back)
                    else -> CatalogueScreen(catalog, savedKeys, pendingKeys, format, onQuery, catalogueScroll,
                        openEvent, { changeBookmark(it, it.savedKey() in savedKeys) }, { dialog = "about" },
                        ready.catalog::retry, ready.catalog::retryMetadata, ready.catalog::loadNext, bundled)
                }
            }
        }
        if (route != "detail" && route != "typography" && !authRoute && !editRoute) AtlasTabBar(route) { route = it }
    }
    val bookmarkFailure = bookmarks.failures.firstOrNull()
    val accountError = savedState?.error
    if(importCandidates != null && accountSaved != null &&
        (!n2 || authState?.canWritePrivate == true && importAccess == authState.privateAccess)) {
        val captured = importCandidates.orEmpty()
        AlertDialog(onDismissRequest = { importCandidates = null },
            title = { Text(stringResource(Res.string.account_copy_title)) },
            text = { Text(stringResource(Res.string.account_copy_body, captured.size, account?.displayName.orEmpty())) },
            containerColor = AtlasColors.SurfaceRaised, titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = {
                if(identity == importOwner && savedState?.writable == true &&
                    (!n2 || importAccess == authState?.privateAccess)) accountSaved.importGuest(captured)
                importCandidates = null
            }) { Text(stringResource(Res.string.account_copy_confirm)) } },
            dismissButton = { TextButton(onClick = { importCandidates = null }) { Text(stringResource(Res.string.auth_cancel)) } })
    }
    if(accountError != null && (route == "home" || route == "detail")) {
        AlertDialog(onDismissRequest = accountSaved::dismissError,
            title = { Text(stringResource(Res.string.account_sync_error_title)) },
            text = { Text(stringResource(when(accountError) {
                SavedFailure.Storage -> Res.string.account_write_error
                SavedFailure.ReadOnly -> Res.string.account_read_only
                SavedFailure.Busy -> Res.string.account_busy
                else -> Res.string.account_sync_error
            }), color = AtlasColors.TextSecondary) },
            containerColor = AtlasColors.SurfaceRaised, titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = { accountSaved.dismissError(); accountSaved.refresh() }) {
                Text(stringResource(Res.string.account_refresh))
            } },
            dismissButton = { TextButton(onClick = accountSaved::dismissError) { Text(stringResource(Res.string.understood)) } })
    } else if (bookmarkFailure != null) {
        val acknowledge = { ready.bookmarks.acknowledgeFailure(bookmarkFailure.id) }
        val body = when (val error = bookmarkFailure.error) {
            BookmarkError.Storage -> stringResource(Res.string.storage_write_error_body)
            is BookmarkError.Catalog -> catalogErrorText(error.error)
        }
        AlertDialog(onDismissRequest = acknowledge, title = { Text(stringResource(Res.string.storage_write_error)) },
            text = { Text(body, color = AtlasColors.TextSecondary) }, containerColor = AtlasColors.SurfaceRaised,
            titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = acknowledge) { Text(stringResource(Res.string.understood), color = AtlasColors.Primary) } })
    } else if(dialog=="discardProfile") {
        AlertDialog(onDismissRequest={dialog=null},title={Text(stringResource(Res.string.profile_discard_title))},
            text={Text(stringResource(Res.string.profile_discard_body))},containerColor=AtlasColors.SurfaceRaised,titleContentColor=AtlasColors.TextPrimary,
            confirmButton={TextButton(onClick={dialog=null;editor?.close();route="profile"}){Text(stringResource(Res.string.profile_discard))}},
            dismissButton={TextButton(onClick={dialog=null}){Text(stringResource(Res.string.profile_continue_edit))}})
    } else if (dialog == "logout" && auth != null) {
        AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.auth_logout_title)) },
            text = { Text(stringResource(Res.string.auth_logout_body), color = AtlasColors.TextSecondary) },
            containerColor = AtlasColors.SurfaceRaised, titleContentColor = AtlasColors.TextPrimary,
            confirmButton = { TextButton(onClick = { dialog = null; auth.logout() }) {
                Text(stringResource(Res.string.auth_logout), color = AtlasColors.Primary)
            } },
            dismissButton = { TextButton(onClick = { dialog = null }) {
                Text(stringResource(Res.string.auth_cancel), color = AtlasColors.TextSecondary)
            } })
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
