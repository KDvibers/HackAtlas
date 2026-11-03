package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.network.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.ui.auth.AuthStore
import dev.hackatlas.mobile.data.saved.*
import dev.hackatlas.mobile.ui.saved.AccountSavedStore
import dev.hackatlas.mobile.ui.profile.ProfileEditStore
import dev.hackatlas.mobile.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.*

/** Process owner: Activity/UIViewController recreation does not reopen files or create clients. */
class CatalogRuntime(source: CatalogSource?, storageFactory: (GuestStoreScope) -> GuestSnapshotStorage,
    catalogEnabled: Boolean = true, remotePageSize: Int = 20,
    secureStoreFactory: ((CatalogSource.Remote) -> SecureTokenStore)? = null,
    accountCacheFactory: ((CatalogSource.Remote) -> AccountCacheStorage)? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: HttpClientEngine? = null
    private var client: HttpClient? = null
    private val authClient = if (source is CatalogSource.Remote && secureStoreFactory != null)
        createAuthSessionClient(source, secureStoreFactory(source)) else null
    private val auth = authClient?.let { AuthStore(it, scope) }
    private val profileEditor=if(auth!=null && authClient!=null) ProfileEditStore(auth,authClient,scope) else null
    val state = AppState(source, scope, storageFactory, remoteRepositoryFactory = { remote ->
        val transport = createPlatformHttpEngine().also { engine = it }
        val api = createApiClient(transport, remote).also { client = it }
        RemoteCatalogRepository(api, remote)
    }, catalogEnabled = catalogEnabled, remotePageSize = remotePageSize, auth = auth, profileEditor=profileEditor,
        accountSavedFactory = if(source is CatalogSource.Remote && auth != null && authClient != null && accountCacheFactory != null)
            { guest, repository, invalidate -> AccountSavedStore(auth, authClient, repository, guest,
                AccountCache(source.baseUrl, accountCacheFactory(source)), scope,
                onInvalidated = invalidate, onChanged = auth::refresh) } else null)

    fun onForeground() {
        auth?.onForeground()
        (state.state.value as? BootstrapState.Ready)?.accountSaved?.refresh()
    }
    fun onBackground() { auth?.onBackground() }

    fun close() {
        scope.cancel()
        authClient?.close()
        client?.close()
        engine?.close()
    }
}
