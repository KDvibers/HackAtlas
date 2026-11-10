package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.network.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.ui.auth.AuthStore
import dev.hackatlas.mobile.ui.auth.EmailVerificationStore
import dev.hackatlas.mobile.data.saved.*
import dev.hackatlas.mobile.ui.saved.AccountSavedStore
import dev.hackatlas.mobile.ui.profile.ProfileEditStore
import dev.hackatlas.mobile.data.applications.ApplicationCache
import dev.hackatlas.mobile.data.applications.ApplicationCacheException
import dev.hackatlas.mobile.ui.applications.ApplicationStore
import dev.hackatlas.mobile.domain.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.*

/** Process owner: Activity/UIViewController recreation does not reopen files or create clients. */
class CatalogRuntime(source: CatalogSource?, storageFactory: (GuestStoreScope) -> GuestSnapshotStorage,
    catalogEnabled: Boolean = true, remotePageSize: Int = 20,
    secureStoreFactory: ((CatalogSource.Remote) -> SecureTokenStore)? = null,
    accountCacheFactory: ((CatalogSource.Remote) -> AccountCacheStorage)? = null,
    applicationCacheFactory: ((CatalogSource.Remote) -> AccountCacheStorage)? = null,
    accessMode: AccountAccessMode = AccountAccessMode.LEGACY) {
    init {
        requireAccountAccessSource(source, accessMode)
        if (accessMode == AccountAccessMode.N2_REQUIRED)
            require(secureStoreFactory != null && accountCacheFactory != null && applicationCacheFactory != null)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: HttpClientEngine? = null
    private var client: HttpClient? = null
    private val savedCache = (source as? CatalogSource.Remote)?.let { remote ->
        accountCacheFactory?.let { AccountCache(remote.baseUrl, it(remote)) }
    }
    private val applicationsCache = (source as? CatalogSource.Remote)?.let { remote ->
        applicationCacheFactory?.let { ApplicationCache(remote.baseUrl, it(remote)) }
    }
    private val authClient = if (source is CatalogSource.Remote && secureStoreFactory != null)
        createAuthSessionClient(source, secureStoreFactory(source),
            if (accessMode == AccountAccessMode.N2_REQUIRED) AuthProtocol.CLOSED_N2 else AuthProtocol.LEGACY,
            clearPrivateCaches = { clearAccountCopies(requireNotNull(savedCache), requireNotNull(applicationsCache)) }) else null
    private val auth = authClient?.let { AuthStore(it, scope) }
    private val verification = authClient?.takeIf { it.protocol == AuthProtocol.CLOSED_N2 }
        ?.let { EmailVerificationStore(N2AccountClient(it), it, scope) }
    private val profileEditor=if(auth!=null && authClient!=null) ProfileEditStore(auth,authClient,scope) else null
    val state = AppState(source, scope, storageFactory, remoteRepositoryFactory = { remote ->
        val transport = createPlatformHttpEngine().also { engine = it }
        val api = createApiClient(transport, remote).also { client = it }
        RemoteCatalogRepository(api, remote)
    }, catalogEnabled = catalogEnabled, remotePageSize = remotePageSize, auth = auth, profileEditor=profileEditor,
        accountSavedFactory = if(auth != null && authClient != null && savedCache != null)
            { guest, repository, invalidate -> AccountSavedStore(auth, authClient, repository, guest,
                savedCache, scope,
                onInvalidated = invalidate, onChanged = auth::refresh) } else null,
        applicationsFactory = if(auth != null && authClient != null && applicationsCache != null)
            { -> ApplicationStore(auth,authClient,applicationsCache,scope,
                onChanged = auth::refresh) } else null,
        emailVerification = verification)

    fun onForeground() {
        auth?.onForeground()
        (state.state.value as? BootstrapState.Ready)?.accountSaved?.refresh()
        (state.state.value as? BootstrapState.Ready)?.applications?.refresh()
    }
    fun onBackground() {
        auth?.onBackground()
        verification?.onBackground()
    }

    fun close() {
        verification?.close()
        scope.cancel()
        authClient?.close()
        client?.close()
        engine?.close()
    }
}

/** Try both stores even if one deletion fails. The auth client keeps access closed on failure. */
internal suspend fun clearAccountCopies(saved: AccountCache, applications: ApplicationCache) {
    var failed = false
    try { saved.clear() } catch (_: AccountCacheException) { failed = true }
    try { applications.clear() } catch (_: ApplicationCacheException) { failed = true }
    if (failed) throw SecureStorageException()
}
