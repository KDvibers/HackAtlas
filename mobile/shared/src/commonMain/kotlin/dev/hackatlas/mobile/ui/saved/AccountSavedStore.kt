package dev.hackatlas.mobile.ui.saved

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.data.saved.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.ui.auth.*
import dev.hackatlas.mobile.ui.savedKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock

enum class SavedMode { CHECKING, GUEST, ACCOUNT, CLEARING, BLOCKED }
sealed interface SavedFailure {
    data class Auth(val error: AuthError) : SavedFailure
    data class Catalog(val error: CatalogError) : SavedFailure
    data object Storage : SavedFailure
    data object ReadOnly : SavedFailure
    data object Busy : SavedFailure
}
data class AccountSavedUiState(
    val mode: SavedMode = SavedMode.CHECKING, val ownerId: String? = null,
    val entries: List<SavedGuestEvent> = emptyList(), val cachedAt: String? = null,
    val writable: Boolean = false, val syncing: Boolean = false,
    val pendingKeys: Set<SavedEventKey> = emptySet(), val error: SavedFailure? = null,
    val imported: Set<String> = emptySet(), val importTotal: Int = 0,
    val access: PrivateAccess? = null,
)

internal fun AccountSavedUiState.visibleFor(auth: AuthUiState?): AccountSavedUiState =
    if (mode == SavedMode.ACCOUNT && (ownerId != auth?.localIdentity?.userId || auth?.canReadPrivate != true ||
        access != auth.privateAccess))
        AccountSavedUiState(mode = SavedMode.CHECKING, access = auth?.privateAccess) else this

/** Main-thread process owner. Every async completion is checked against current session/epoch. */
class AccountSavedStore(
    private val auth: AuthStore, private val client: AuthSessionClient,
    private val public: CatalogRepository, private val guest: GuestSavedRepository,
    private val cache: AccountCache, private val scope: CoroutineScope,
    private val pageSize: Int = 50, private val onInvalidated: () -> Unit = {},
    private val onChanged: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(AccountSavedUiState())
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var owner: String? = null
    private var destination: SavedMode? = null
    private var transitionJob: Job? = null
    private var operation: Job? = null
    private var document: AccountCacheDocument? = null
    private var access: PrivateAccess? = null

    init {
        require(pageSize in 1..50)
        require(guest.scope == GuestStoreScope.Remote(cache.apiBaseUrl))
        scope.launch { auth.state.collect(::observeAuth) }
    }

    private fun verified(user: String?) = user != null && if (access != null)
        access == auth.currentPrivateAccess() && access?.canWrite == true
    else auth.state.value.canWritePrivate && auth.state.value.localIdentity?.userId == user

    private fun current(turn: Long, user: String) = scope.isActive && turn == epoch && owner == user &&
        if (access != null) access == auth.currentPrivateAccess() && access?.canRead == true
        else auth.state.value.localIdentity?.userId == user && auth.state.value.busy != AuthOperation.LOGOUT

    private fun observeAuth(value: AuthUiState) {
        val user = value.localIdentity?.userId.takeUnless { value.busy == AuthOperation.LOGOUT }
        if (value.privateAccess != null) {
            val next = when {
                value.session == AuthState.StorageUnavailable -> SavedMode.BLOCKED
                user != null && value.canReadPrivate -> SavedMode.ACCOUNT
                user != null || value.session == AuthState.NotLoaded || value.busy == AuthOperation.LOGOUT -> SavedMode.CHECKING
                else -> SavedMode.GUEST
            }
            if (owner != user || destination != next || access != value.privateAccess)
                transition(user, next, value.privateAccess)
            return
        }
        // Keep the existing file while secure storage is being read; never guess its owner.
        if(user == null && value.session == AuthState.NotLoaded && value.busy != AuthOperation.LOGOUT) return
        val next = when {
            user != null -> SavedMode.ACCOUNT
            value.busy == AuthOperation.LOGOUT -> SavedMode.CHECKING
            value.session == AuthState.StorageUnavailable -> SavedMode.BLOCKED
            else -> SavedMode.GUEST
        }
        if(owner != user || destination != next) transition(user, next)
        else if(mutableState.value.mode == SavedMode.ACCOUNT) {
            val wasWritable = mutableState.value.writable
            mutableState.value = mutableState.value.copy(writable = verified(user))
            if(!wasWritable && verified(user)) refresh()
        }
    }

    private fun transition(user: String?, next: SavedMode, newAccess: PrivateAccess? = null) {
        epoch++; val turn = epoch
        operation?.cancel(); transitionJob?.cancel(); document = null
        val previous = access
        owner = user; destination = next; access = newAccess
        if (newAccess == null || previous?.canRead == true && (!newAccess.canRead || previous.identity != newAccess.identity))
            onInvalidated()
        if (newAccess != null && next != SavedMode.ACCOUNT) {
            mutableState.value = AccountSavedUiState(mode = next, ownerId = user, access = newAccess,
                error = if (next == SavedMode.BLOCKED) SavedFailure.Auth(AuthError.Storage) else null)
            return
        }
        mutableState.value = AccountSavedUiState(mode = SavedMode.CLEARING, ownerId = user)
        transitionJob = scope.launch {
            try {
                if(user == null) {
                    cache.clear()
                    if(turn == epoch) mutableState.value = AccountSavedUiState(mode = next,
                        error = if(next == SavedMode.BLOCKED) SavedFailure.Auth(AuthError.Storage) else null)
                } else {
                    val doc = cache.open(user, newAccess?.identity?.generation)
                    if(!current(turn, user)) return@launch
                    document = doc
                    mutableState.value = AccountSavedUiState(SavedMode.ACCOUNT, user, doc.entries, doc.cachedAt,
                        writable = verified(user), imported = doc.imported, access = newAccess)
                    if(verified(user)) refresh()
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: AccountCacheException) {
                if(turn == epoch) mutableState.value = AccountSavedUiState(SavedMode.BLOCKED, user, error = SavedFailure.Storage)
            }
        }
    }

    fun retryCleanup() {
        if (access != null && auth.state.value.session == AuthState.StorageUnavailable) auth.refresh()
        else transition(owner, destination ?: SavedMode.GUEST, access)
    }
    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }

    fun refresh() {
        if((access == null && auth.state.value.error != null) || access?.canWrite == false) { auth.refresh(); return }
        perform(allowReadOnly = true) { turn, user -> refreshDocument(turn, user) }
    }

    fun change(event: Hackathon, remove: Boolean) = perform(setOf(event.savedKey())) { turn, user ->
        val captured = access
        if(remove) client.unsave(user, event.id, captured) else {
            checkedDetail(event)
            if(!current(turn, user)) return@perform
            client.save(user, event.id, captured)
        }
        refreshDocument(turn, user)
        if(current(turn, user)) onChanged()
    }

    /** Invoke only after a visible confirmation for this account. Does not mutate guest data. */
    fun importGuest(captured: List<SavedGuestEvent>) {
        val candidates = captured.toList()
        perform(candidates.map { it.key }.toSet()) { turn, user ->
            val capturedAccess = access
            mutableState.value = mutableState.value.copy(importTotal = candidates.size)
            for(snapshot in candidates) {
                if(!current(turn, user)) return@perform
                val key = journalKey(snapshot.key)
                if(key in document!!.imported) continue
                checkedDetail(snapshot.detail.hackathon)
                if(!current(turn, user)) return@perform
                client.save(user, snapshot.key.id, capturedAccess)
                val next = document!!.copy(imported = document!!.imported + key)
                commit(next, turn, user)
            }
            refreshDocument(turn, user)
            if(current(turn, user)) onChanged()
        }
    }

    private suspend fun checkedDetail(event: Hackathon): HackathonDetail {
        val detail = public.detail(event.id)
        if(detail.hackathon.savedKey() != event.savedKey()) throw CatalogException(CatalogError.InvalidResponse)
        return detail
    }

    private suspend fun refreshDocument(turn: Long, user: String) {
        val events = scanMembership(turn, user) ?: return
        if(events.size > pageSize) {
            // Offset pages have no snapshot revision in Starter. A bounded second
            // ordered scan detects observed shifts without promising serializability.
            val verified = scanMembership(turn, user) ?: return
            if(events.map { it.savedKey() } != verified.map { it.savedKey() })
                throw AuthException(AuthError.InvalidResponse)
        }
        val snapshots = events.map { event ->
            SavedGuestEvent(checkedDetail(event), Clock.System.now().toString())
        }
        if(!current(turn, user)) return
        commit(document!!.copy(entries = snapshots, cachedAt = Clock.System.now().toString()), turn, user)
    }

    private suspend fun scanMembership(turn: Long, user: String): List<Hackathon>? {
        val events = mutableListOf<Hackathon>()
        var page = 0; var total: Int? = null
        do {
            if(!current(turn, user)) return null
            val response = client.listSaved(user, page, pageSize, access)
            if(!current(turn, user)) return null
            if(total != null && total != response.totalElements) throw AuthException(AuthError.InvalidResponse)
            total = response.totalElements
            events += response.items
            if(events.map { it.id }.distinct().size != events.size || page >= 199)
                throw AuthException(AuthError.InvalidResponse)
            page++
        } while(response.hasNext)
        if(events.size != total) throw AuthException(AuthError.InvalidResponse)
        return events
    }

    private suspend fun commit(next: AccountCacheDocument, turn: Long, user: String) {
        if(!cache.write(next) { current(turn, user) }) return
        if(!current(turn, user)) return
        document = next
        mutableState.value = mutableState.value.copy(entries = next.entries, cachedAt = next.cachedAt,
            imported = next.imported, writable = verified(user))
    }

    private fun perform(pending: Set<SavedEventKey> = emptySet(), allowReadOnly: Boolean = false, action: suspend (Long, String) -> Unit) {
        val value = mutableState.value
        if(value.mode != SavedMode.ACCOUNT || !verified(owner) || (!allowReadOnly && !value.writable)) {
            mutableState.value = value.copy(error = SavedFailure.ReadOnly); return
        }
        if(value.syncing) { mutableState.value = value.copy(error = SavedFailure.Busy); return }
        val turn = epoch; val user = owner ?: return
        mutableState.value = value.copy(syncing = true, pendingKeys = pending, error = null)
        operation = scope.launch {
            try { action(turn, user) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failed: AuthException) { fail(turn, user, SavedFailure.Auth(failed.error)) }
            catch(failed: CatalogException) {
                if (current(turn, user) && (failed.error == CatalogError.Network || failed.error == CatalogError.Timeout))
                    client.privateTransportUnavailable(access)
                fail(turn, user, SavedFailure.Catalog(failed.error))
            }
            catch(_: AccountCacheException) { fail(turn, user, SavedFailure.Storage) }
            catch(_: IllegalArgumentException) { fail(turn, user, SavedFailure.Auth(AuthError.InvalidResponse)) }
            finally {
                if(current(turn, user)) mutableState.value = mutableState.value.copy(syncing = false, pendingKeys = emptySet())
            }
        }
    }
    private fun fail(turn: Long, user: String, failure: SavedFailure) {
        val unavailable = failure is SavedFailure.Auth && failure.error in setOf(AuthError.Network, AuthError.Timeout, AuthError.Unauthorized, AuthError.Storage)
        if(current(turn, user)) mutableState.value = mutableState.value.copy(error = failure,
            writable = mutableState.value.writable && !unavailable)
    }
}

fun journalKey(key: SavedEventKey) = "${key.isDemo}:${key.id}"
