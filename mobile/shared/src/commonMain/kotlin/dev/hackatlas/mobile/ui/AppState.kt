package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi

sealed interface BootstrapState {
    data object Loading : BootstrapState
    data class Ready(
        val repository: DemoRepository?,
        val saved: GuestSavedRepository,
        val catalog: CatalogStore,
        val details: DetailStore,
        val bookmarks: BookmarkStore,
    ) : BootstrapState
    data object StorageFailed : BootstrapState
    data object ConfigurationUnavailable : BootstrapState
}

/** One instance per process. Network requests never gate access to the local store. */
@OptIn(ExperimentalResourceApi::class)
class AppState(
    val source: CatalogSource?,
    val ownerScope: CoroutineScope,
    private val storageFactory: (GuestStoreScope) -> GuestSnapshotStorage,
    private val remoteRepositoryFactory: (CatalogSource.Remote) -> CatalogRepository = { error("Remote factory required") },
    private val fixtureSource: FixtureSource = FixtureSource { name -> Res.readBytes("files/demo/$name").decodeToString() },
    private val catalogEnabled: Boolean = true,
) {
    private val mutableState = MutableStateFlow<BootstrapState>(BootstrapState.Loading)
    val state = mutableState.asStateFlow()
    private val loadMutex = Mutex()

    suspend fun load() = loadMutex.withLock {
        if (mutableState.value is BootstrapState.Ready) return@withLock
        val selected = source ?: run {
            mutableState.value = BootstrapState.ConfigurationUnavailable
            return@withLock
        }
        mutableState.value = BootstrapState.Loading
        try {
            val demo = if (selected == CatalogSource.BundledDemo && catalogEnabled) {
                try { DemoRepository.load(fixtureSource) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } else null
            val guestScope = when (selected) {
                CatalogSource.BundledDemo -> GuestStoreScope.BundledDemo
                is CatalogSource.Remote -> GuestStoreScope.Remote(selected.baseUrl)
            }
            val saved = GuestSavedRepository.open(storageFactory(guestScope), guestScope,
                seed = demo?.savedHackathons()?.mapNotNull { demo.detail(it.id) }.orEmpty())
            val repository = when {
                !catalogEnabled -> UnavailableCatalog
                selected is CatalogSource.Remote -> remoteRepositoryFactory(selected)
                demo != null -> DemoCatalogRepository(demo)
                else -> UnavailableCatalog
            }
            mutableState.value = BootstrapState.Ready(demo, saved,
                CatalogStore(repository, ownerScope, initialSort = if (selected == CatalogSource.BundledDemo) CatalogSort.NEWEST else CatalogSort.DEADLINE_ASC), DetailStore(repository, ownerScope),
                BookmarkStore(RemoteSaveCoordinator(repository, saved), ownerScope))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GuestStorageException) {
            mutableState.value = BootstrapState.StorageFailed
        }
    }
}

private object UnavailableCatalog : CatalogRepository {
    override suspend fun catalog(query: CatalogQuery): CatalogPage = unavailable()
    override suspend fun detail(id: String): HackathonDetail = unavailable()
    override suspend fun filters(): FilterMetadata = unavailable()
    private fun unavailable(): Nothing = throw CatalogException(CatalogError.Network)
}
