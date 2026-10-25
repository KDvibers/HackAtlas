package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Starts catalogue and metadata independently. The host owns and cancels [scope].
 * Commands and completions share one queue, so state transitions stay serialized even
 * on a multi-threaded dispatcher. Public commands enqueue work and never block the caller.
 */
class CatalogStore(private val repository: CatalogRepository, private val scope: CoroutineScope,
    initialSort: CatalogSort = CatalogSort.DEADLINE_ASC) {
    private val mutableState = MutableStateFlow(CatalogUiState(query = CatalogQuery(sort = initialSort)))
    val state = mutableState.asStateFlow()
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private var generation = 0L
    private var pageJob: Job? = null
    private var pendingPage: Int? = null

    init {
        scope.launch {
            firstPage(mutableState.value.query)
            metadata()
            for (action in actions) handle(action)
        }.invokeOnCompletion { actions.cancel() }
    }

    /** A new filter set always starts at page zero; only nonblank text-only changes debounce. */
    fun setQuery(query: CatalogQuery) {
        require(query.format != EventFormat.UNSUPPORTED) { "Unsupported format is not an API filter" }
        require(query.registrationStatus != RegistrationStatus.UNSUPPORTED) { "Unsupported registration status is not an API filter" }
        actions.trySend(Action.SetQuery(query.copy(page = 0)))
    }

    fun loadNext() { actions.trySend(Action.LoadNext) }
    fun retry() { actions.trySend(Action.Retry) }
    fun retryMetadata() { actions.trySend(Action.RetryMetadata) }

    private fun handle(action: Action) {
        when (action) {
            is Action.SetQuery -> {
                val previous = mutableState.value.query
                if (action.query == previous) return
                val onlyTextChanged = action.query.copy(query = previous.query) == previous
                firstPage(action.query, debounce = onlyTextChanged && action.query.query.isNotBlank())
            }
            Action.LoadNext -> {
                val current = mutableState.value
                if (current.loading || current.loadingMore || current.error != null || current.appendError != null) return
                val page = current.nextPage ?: return
                requestPage(current.query.copy(page = page))
            }
            Action.Retry -> {
                val current = mutableState.value
                if (current.loading || current.loadingMore) return
                if (current.appendError != null) {
                    current.nextPage?.let { requestPage(current.query.copy(page = it)) }
                } else if (current.error != null || current.asOf == null) {
                    firstPage(current.query)
                }
            }
            Action.RetryMetadata -> {
                val current = mutableState.value
                if (!current.metadataLoading && (current.metadataError != null || current.metadata == null)) metadata()
            }
            is Action.PageReady -> if (isCurrent(action.generation, action.requestedPage)) {
                acceptPage(action.requestedPage, action.page)
            }
            is Action.PageFailed -> if (isCurrent(action.generation, action.page)) {
                failPage(action.page, action.error)
            }
            is Action.PageCancelled -> if (isCurrent(action.generation, action.page)) {
                pendingPage = null
                mutableState.value = mutableState.value.copy(loading = false, loadingMore = false)
            }
            is Action.MetadataReady -> mutableState.value = mutableState.value.copy(
                metadata = action.value, metadataLoading = false, metadataError = null)
            is Action.MetadataFailed -> mutableState.value = mutableState.value.copy(
                metadataLoading = false, metadataError = action.error)
            Action.MetadataCancelled -> mutableState.value = mutableState.value.copy(metadataLoading = false)
        }
    }

    private fun firstPage(query: CatalogQuery, debounce: Boolean = false) {
        generation++
        pageJob?.cancel()
        mutableState.value = mutableState.value.copy(
            query = query, items = emptyList(), nextPage = null, asOf = null,
            loading = true, loadingMore = false, error = null, appendError = null)
        requestPage(query, debounce)
    }

    private fun requestPage(query: CatalogQuery, debounce: Boolean = false) {
        val requestGeneration = generation
        pendingPage = query.page
        mutableState.value = mutableState.value.copy(
            loading = query.page == 0, loadingMore = query.page != 0, error = null, appendError = null)
        pageJob = scope.launch {
            try {
                if (debounce) delay(300)
                val page = repository.catalog(query)
                actions.trySend(Action.PageReady(requestGeneration, query.page, page))
            } catch (cancelled: CancellationException) {
                actions.trySend(Action.PageCancelled(requestGeneration, query.page))
                throw cancelled
            } catch (failure: CatalogException) {
                actions.trySend(Action.PageFailed(requestGeneration, query.page, failure.error))
            }
        }
    }

    private fun isCurrent(epoch: Long, page: Int): Boolean = epoch == generation && page == pendingPage

    private fun acceptPage(requestedPage: Int, page: CatalogPage) {
        val current = mutableState.value
        val previous = if (requestedPage == 0) emptyList() else current.items
        val combined = (previous + page.items).distinctBy { it.id }
        val invalid = page.page != requestedPage || page.size != current.query.size ||
            (page.hasNext && (page.items.isEmpty() || requestedPage == Int.MAX_VALUE ||
                (requestedPage > 0 && combined.size == previous.size)))
        if (invalid) {
            failPage(requestedPage, CatalogError.InvalidResponse)
            return
        }
        pendingPage = null
        mutableState.value = current.copy(
            items = combined, nextPage = if (page.hasNext) requestedPage + 1 else null, asOf = page.asOf,
            loading = false, loadingMore = false, error = null, appendError = null)
    }

    private fun failPage(page: Int, error: CatalogError) {
        pendingPage = null
        mutableState.value = if (page == 0) mutableState.value.copy(loading = false, error = error)
        else mutableState.value.copy(loadingMore = false, appendError = error)
    }

    private fun metadata() {
        mutableState.value = mutableState.value.copy(metadataLoading = true, metadataError = null)
        scope.launch {
            try {
                actions.trySend(Action.MetadataReady(repository.filters()))
            } catch (cancelled: CancellationException) {
                actions.trySend(Action.MetadataCancelled)
                throw cancelled
            } catch (failure: CatalogException) {
                actions.trySend(Action.MetadataFailed(failure.error))
            }
        }
    }

    private sealed interface Action {
        data class SetQuery(val query: CatalogQuery) : Action
        data object LoadNext : Action
        data object Retry : Action
        data object RetryMetadata : Action
        data class PageReady(val generation: Long, val requestedPage: Int, val page: CatalogPage) : Action
        data class PageFailed(val generation: Long, val page: Int, val error: CatalogError) : Action
        data class PageCancelled(val generation: Long, val page: Int) : Action
        data class MetadataReady(val value: FilterMetadata) : Action
        data class MetadataFailed(val error: CatalogError) : Action
        data object MetadataCancelled : Action
    }
}
