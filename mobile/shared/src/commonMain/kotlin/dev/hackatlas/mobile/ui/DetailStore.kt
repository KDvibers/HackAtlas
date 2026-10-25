package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.domain.CatalogError
import dev.hackatlas.mobile.domain.CatalogException
import dev.hackatlas.mobile.domain.CatalogRepository
import dev.hackatlas.mobile.domain.HackathonDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The host owns [scope]. Every open/snapshot invalidates previous requests, including the same ID. */
class DetailStore(private val repository: CatalogRepository, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow<DetailUiState>(DetailUiState.Idle)
    val state = mutableState.asStateFlow()
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private var generation = 0L
    private var request: Job? = null

    init {
        scope.launch { for (action in actions) handle(action) }
            .invokeOnCompletion { actions.cancel() }
    }

    fun open(id: String) { actions.trySend(Action.Open(id)) }
    fun retry() { actions.trySend(Action.Retry) }
    fun showSnapshot(saved: SavedGuestEvent) { actions.trySend(Action.Snapshot(saved)) }

    private fun handle(action: Action) {
        when (action) {
            is Action.Open -> begin(action.id)
            Action.Retry -> (mutableState.value as? DetailUiState.Error)?.let { begin(it.id) }
            is Action.Snapshot -> {
                generation++
                request?.cancel()
                mutableState.value = DetailUiState.Content(action.saved.detail, action.saved)
            }
            is Action.Ready -> if (action.generation == generation) {
                mutableState.value = DetailUiState.Content(action.detail)
            }
            is Action.Failed -> if (action.generation == generation) {
                mutableState.value = DetailUiState.Error(action.id, action.error)
            }
            is Action.Cancelled -> if (action.generation == generation) {
                mutableState.value = DetailUiState.Idle
            }
        }
    }

    private fun begin(id: String) {
        val requestGeneration = ++generation
        request?.cancel()
        mutableState.value = DetailUiState.Loading(id)
        request = scope.launch {
            try {
                actions.trySend(Action.Ready(requestGeneration, repository.detail(id)))
            } catch (cancelled: CancellationException) {
                actions.trySend(Action.Cancelled(requestGeneration))
                throw cancelled
            } catch (failure: CatalogException) {
                actions.trySend(Action.Failed(requestGeneration, id, failure.error))
            }
        }
    }

    private sealed interface Action {
        data class Open(val id: String) : Action
        data object Retry : Action
        data class Snapshot(val saved: SavedGuestEvent) : Action
        data class Ready(val generation: Long, val detail: HackathonDetail) : Action
        data class Failed(val generation: Long, val id: String, val error: CatalogError) : Action
        data class Cancelled(val generation: Long) : Action
    }
}
