package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.DemoRepository
import dev.hackatlas.mobile.data.FixtureSource
import dev.hackatlas.mobile.data.GuestSnapshotStorage
import dev.hackatlas.mobile.data.GuestSavedRepository
import dev.hackatlas.mobile.data.GuestStorageException
import dev.hackatlas.mobile.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi

sealed interface BootstrapState {
    data object Loading : BootstrapState
    data class Ready(val repository: DemoRepository?, val saved: GuestSavedRepository) : BootstrapState
    data object StorageFailed : BootstrapState
}

@OptIn(ExperimentalResourceApi::class)
class AppState(
    private val storage: GuestSnapshotStorage,
    private val source: FixtureSource = FixtureSource { name -> Res.readBytes("files/demo/$name").decodeToString() },
) {
    private val mutableState = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val state = mutableState.asStateFlow()
    private val loadMutex = Mutex()

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load() = loadMutex.withLock {
        if (mutableState.value is BootstrapState.Ready) return@withLock
        mutableState.value = BootstrapState.Loading
        try {
            val repository = try { DemoRepository.load(source) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val saved = GuestSavedRepository.open(
                storage,
                seed = repository?.savedHackathons()?.mapNotNull { repository.detail(it.id) }.orEmpty(),
            )
            mutableState.value = BootstrapState.Ready(repository, saved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GuestStorageException) {
            mutableState.value = BootstrapState.StorageFailed
        }
    }
}
