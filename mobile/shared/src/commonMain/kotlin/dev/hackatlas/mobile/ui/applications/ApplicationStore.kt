package dev.hackatlas.mobile.ui.applications

import dev.hackatlas.mobile.data.applications.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.ui.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

enum class ApplicationMode { CHECKING, GUEST, ACCOUNT, CLEARING, BLOCKED }
sealed interface ApplicationFailure {
    data class Auth(val error: AuthError) : ApplicationFailure
    data object Storage : ApplicationFailure
    data object ReadOnly : ApplicationFailure
    data object Busy : ApplicationFailure
}
data class ApplicationUiState(
    val mode: ApplicationMode = ApplicationMode.CHECKING, val ownerId: String? = null,
    val marks: List<ApplicationMark> = emptyList(), val cachedAt: String? = null,
    val known: Boolean = false, val fromCache: Boolean = true, val writable: Boolean = false,
    val syncing: Boolean = false, val pendingId: String? = null, val uncertainId: String? = null,
    val error: ApplicationFailure? = null,
) {
    fun markFor(id: String) = marks.firstOrNull { it.hackathonId.equals(id, ignoreCase = true) }
}

/** Hide private values immediately; the async lifecycle collector may run a frame later. */
internal fun ApplicationUiState.visibleFor(userId: String?, loggingOut: Boolean): ApplicationUiState =
    if(mode == ApplicationMode.ACCOUNT && (ownerId != userId || loggingOut))
        ApplicationUiState(mode = ApplicationMode.CHECKING) else this

/** Main-thread process owner; pagination is authoritative only after a complete scan. */
class ApplicationStore(
    private val auth: AuthStore, private val client: AuthSessionClient,
    private val cache: ApplicationCache, private val scope: CoroutineScope,
    private val pageSize: Int = 50, private val now: () -> Instant = { Clock.System.now() },
    private val onChanged: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ApplicationUiState())
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var owner: String? = null
    private var destination: ApplicationMode? = null
    private var operation: Job? = null
    private var transitionJob: Job? = null
    private var document: ApplicationCacheDocument? = null
    private var reconciled = false
    private var authenticated = false
    private var refreshPending = false

    init {
        require(pageSize in 1..50)
        scope.launch { auth.state.collect(::observeAuth) }
    }
    private fun verified(user: String?) = user != null && auth.state.value.let {
        it.localIdentity?.userId == user && (it.session as? AuthState.Authenticated)?.user?.id == user &&
            it.busy != AuthOperation.LOGOUT && it.error == null
    }
    private fun current(turn: Long, user: String) = scope.isActive && turn == epoch && owner == user &&
        auth.state.value.localIdentity?.userId == user && auth.state.value.busy != AuthOperation.LOGOUT

    private fun observeAuth(value: AuthUiState) {
        val user = value.localIdentity?.userId?.takeUnless { value.busy == AuthOperation.LOGOUT }
        if(user == null && value.session == AuthState.NotLoaded && value.busy != AuthOperation.LOGOUT) return
        val was = authenticated
        authenticated = verified(user)
        val next = when {
            user != null -> ApplicationMode.ACCOUNT
            value.busy == AuthOperation.LOGOUT -> ApplicationMode.CHECKING
            value.session == AuthState.StorageUnavailable -> ApplicationMode.BLOCKED
            else -> ApplicationMode.GUEST
        }
        if(owner != user || destination != next) transition(user, next)
        else if(mutableState.value.mode == ApplicationMode.ACCOUNT) {
            val state = mutableState.value
            mutableState.value = state.copy(writable = authenticated && reconciled && !state.syncing,
                fromCache = state.fromCache || (!authenticated && state.error != ApplicationFailure.Storage),
                error = state.error ?: value.error?.let(ApplicationFailure::Auth))
            if(!was && authenticated) {
                if(state.syncing) refreshPending = true else refresh()
            }
        }
    }

    private fun transition(user: String?, next: ApplicationMode, reset: Boolean = false) {
        epoch++;val turn = epoch
        operation?.cancel();transitionJob?.cancel()
        owner = user;destination = next;document = null;reconciled = false;refreshPending = false
        mutableState.value = ApplicationUiState(mode = ApplicationMode.CLEARING, ownerId = user)
        transitionJob = scope.launch {
            try {
                if(user == null) {
                    cache.clear()
                    if(turn == epoch) mutableState.value = ApplicationUiState(mode = next)
                } else {
                    if(reset) cache.clear()
                    val doc = cache.open(user)
                    if(!current(turn,user)) return@launch
                    document = doc
                    mutableState.value = ApplicationUiState(mode = ApplicationMode.ACCOUNT, ownerId = user,
                        marks = doc.marks, cachedAt = doc.cachedAt, known = doc.cachedAt != null,
                        error = auth.state.value.error?.let(ApplicationFailure::Auth))
                    if(verified(user)) refresh()
                }
            } catch(canceled: CancellationException) { throw canceled }
            catch(_: ApplicationCacheException) {
                if(turn == epoch) mutableState.value = ApplicationUiState(ApplicationMode.BLOCKED,user,error = ApplicationFailure.Storage)
            }
        }
    }

    fun retryCleanup() = transition(owner, destination ?: ApplicationMode.GUEST, reset = true)

    fun refresh() {
        if(auth.state.value.error != null) { auth.refresh();return }
        perform(null) { turn,user ->
            val marks = scan(turn,user) ?: return@perform
            if(marks.size > pageSize && marks != scan(turn,user)) throw AuthException(AuthError.InvalidResponse)
            if(!current(turn,user)) return@perform
            publishAndPersist(document!!.copy(marks = marks,cachedAt = now().toString()),turn,user)
        }
    }

    fun change(id: String, remove: Boolean) {
        if(!USER_ID.matches(id)) {
            mutableState.value = mutableState.value.copy(error = ApplicationFailure.Auth(AuthError.InvalidResponse));return
        }
        val value = mutableState.value
        if(value.writable && !value.syncing && value.known && (value.markFor(id) != null) == !remove) return
        perform(id) { turn,user ->
            val before = document ?: return@perform
            val mark = if(remove) { client.removeApplication(user,id);null } else client.markApplied(user,id)
            if(!current(turn,user)) return@perform
            val rest = before.marks.filterNot { it.hackathonId.equals(id,ignoreCase = true) }
            val next = if(mark == null) rest else listOf(mark) + rest
            // Server success is retained even if profile refresh or disk persistence fails.
            publishAndPersist(before.copy(marks = next,cachedAt = now().toString()),turn,user,notify = true)
        }
    }

    private suspend fun scan(turn: Long,user: String): List<ApplicationMark>? {
        val marks = mutableListOf<ApplicationMark>();var page = 0;var total: Int? = null
        do {
            val response = client.listApplications(user,page,pageSize)
            if(!current(turn,user)) return null
            if(total != null && total != response.totalElements) throw AuthException(AuthError.InvalidResponse)
            total = response.totalElements;marks += response.items
            if(marks.map { it.hackathonId.lowercase() }.distinct().size != marks.size || page >= 199)
                throw AuthException(AuthError.InvalidResponse)
            page++
        } while(response.hasNext)
        if(marks.size != total) throw AuthException(AuthError.InvalidResponse)
        return marks
    }

    private suspend fun publishAndPersist(next: ApplicationCacheDocument,turn: Long,user: String,notify: Boolean = false) {
        if(!current(turn,user)) return
        document = next;reconciled = true
        mutableState.value = mutableState.value.copy(marks = next.marks,cachedAt = next.cachedAt,
            known = true,fromCache = false,uncertainId = null,error = null)
        if(notify) onChanged()
        cache.write(next) { current(turn,user) }
    }

    private fun perform(id: String?, action: suspend (Long,String) -> Unit) {
        val value = mutableState.value
        if(value.mode != ApplicationMode.ACCOUNT || !verified(owner) || (id != null && !value.writable)) {
            mutableState.value = value.copy(error = value.error ?: ApplicationFailure.ReadOnly);return
        }
        if(value.syncing) return
        val user = owner ?: return;val turn = epoch
        mutableState.value = value.copy(syncing = true,pendingId = id,error = null,writable = false)
        operation = scope.launch {
            try { action(turn,user) }
            catch(canceled: CancellationException) { throw canceled }
            catch(failed: AuthException) { fail(turn,user,ApplicationFailure.Auth(failed.error),id) }
            catch(_: ApplicationCacheException) { fail(turn,user,ApplicationFailure.Storage,null) }
            catch(_: IllegalArgumentException) { fail(turn,user,ApplicationFailure.Auth(AuthError.InvalidResponse),id) }
            finally {
                if(current(turn,user)) {
                    val state = mutableState.value
                    mutableState.value = state.copy(syncing = false,pendingId = null,
                        writable = verified(user) && reconciled && state.error == null)
                    if(refreshPending) { refreshPending = false;refresh() }
                }
            }
        }
    }
    private fun fail(turn: Long,user: String,error: ApplicationFailure,uncertainId: String?) {
        if(!current(turn,user)) return
        reconciled = false
        mutableState.value = mutableState.value.copy(writable = false,error = error,
            fromCache = error != ApplicationFailure.Storage,
            uncertainId = uncertainId ?: mutableState.value.uncertainId)
    }
}
