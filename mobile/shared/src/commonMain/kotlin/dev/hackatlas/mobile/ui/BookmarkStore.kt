package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.GuestStorageException
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BookmarkError {
    data object Storage : BookmarkError
    data class Catalog(val error: CatalogError) : BookmarkError
}

data class BookmarkFailure(val id: Long, val key: SavedEventKey, val error: BookmarkError)
data class BookmarkUiState(
    val pendingKeys: Set<SavedEventKey> = emptySet(),
    val failures: List<BookmarkFailure> = emptyList(),
)

/** Process-owned operation feedback is replayed to a recreated screen until acknowledged. */
class BookmarkStore(private val coordinator: RemoteSaveCoordinator, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(BookmarkUiState())
    val state = mutableState.asStateFlow()
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private var failureId = 0L

    init {
        scope.launch { for (action in actions) handle(action) }
            .invokeOnCompletion { actions.cancel() }
    }

    fun change(event: Hackathon, remove: Boolean, snapshot: SavedGuestEvent? = null) {
        actions.trySend(Action.Change(event, remove, snapshot))
    }

    fun acknowledgeFailure(id: Long) { actions.trySend(Action.Acknowledge(id)) }

    private fun handle(action: Action) {
        val current = mutableState.value
        when (action) {
            is Action.Change -> {
                val key = action.event.savedKey()
                if (key in current.pendingKeys) return
                mutableState.value = current.copy(pendingKeys = current.pendingKeys + key)
                scope.launch {
                    var failure: BookmarkError? = null
                    try {
                        when {
                            action.remove -> coordinator.remove(key)
                            action.snapshot != null -> coordinator.restore(action.snapshot)
                            else -> coordinator.save(action.event)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GuestStorageException) {
                        failure = BookmarkError.Storage
                    } catch (error: CatalogException) {
                        failure = BookmarkError.Catalog(error.error)
                    } finally {
                        actions.trySend(Action.Finished(key, failure))
                    }
                }
            }
            is Action.Finished -> mutableState.value = current.copy(
                pendingKeys = current.pendingKeys - action.key,
                failures = if (action.error == null) current.failures
                    else current.failures + BookmarkFailure(++failureId, action.key, action.error),
            )
            is Action.Acknowledge -> mutableState.value = current.copy(failures = current.failures.filterNot { it.id == action.id })
        }
    }

    private sealed interface Action {
        data class Change(val event: Hackathon, val remove: Boolean, val snapshot: SavedGuestEvent?) : Action
        data class Finished(val key: SavedEventKey, val error: BookmarkError?) : Action
        data class Acknowledge(val id: Long) : Action
    }
}
