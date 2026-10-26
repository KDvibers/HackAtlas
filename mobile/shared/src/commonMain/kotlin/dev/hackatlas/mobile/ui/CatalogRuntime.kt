package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.network.*
import dev.hackatlas.mobile.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.*

/** Process owner: Activity/UIViewController recreation does not reopen files or create clients. */
class CatalogRuntime(source: CatalogSource?, storageFactory: (GuestStoreScope) -> GuestSnapshotStorage,
    catalogEnabled: Boolean = true, remotePageSize: Int = 20) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: HttpClientEngine? = null
    private var client: HttpClient? = null
    val state = AppState(source, scope, storageFactory, remoteRepositoryFactory = { remote ->
        val transport = createPlatformHttpEngine().also { engine = it }
        val api = createApiClient(transport, remote).also { client = it }
        RemoteCatalogRepository(api, remote)
    }, catalogEnabled = catalogEnabled, remotePageSize = remotePageSize)

    fun close() {
        scope.cancel()
        client?.close()
        engine?.close()
    }
}
