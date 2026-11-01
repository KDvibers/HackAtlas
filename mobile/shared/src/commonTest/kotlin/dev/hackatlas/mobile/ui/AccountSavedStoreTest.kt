package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.data.saved.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.ui.auth.*
import dev.hackatlas.mobile.ui.saved.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSavedStoreTest {
    private val source = CatalogSource.Remote("https://account.example.org/api/v1/")
    private val profile = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private val events = StoreTestFixtures.events
    private val at = "2026-11-01T12:00:00Z"
    private class CacheDisk : AccountCacheStorage {
        var value: String? = null; var failClear = false
        override suspend fun read() = value
        override suspend fun write(contents: String) { value = contents }
        override suspend fun clear() { if(failClear) error("denied"); value = null }
    }
    private class GuestDisk : GuestSnapshotStorage {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(contents: String) { value = contents }
    }
    private inner class Setup(val scope: TestScope, val cacheDisk: CacheDisk = CacheDisk()) {
        var offline = false; var failPut: String? = null
        var server = mutableListOf(events[0]); val puts = mutableListOf<String>()
        var onPage: (suspend () -> Unit)? = null
        val tokenStore = object : SecureTokenStore {
            override val apiBaseUrl = source.baseUrl
            var value: StoredSession? = StoredSession("synthetic-hub-" + "h".repeat(40), "2026-12-01T00:00:00Z", profile.id)
            override suspend fun read() = value
            override suspend fun write(session: StoredSession) { value = session }
            override suspend fun clear() { value = null }
        }
        val client = AuthSessionClient(MockEngine.create {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { req ->
                if(offline) throw kotlinx.io.IOException("offline")
                val path = req.url.encodedPath
                when {
                    path == "/api/v1/me" -> respond(CanonicalFixtures.files.getValue("profile.json"), headers = jsonHeaders)
                    path == "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                    path == "/api/v1/me/saved" -> {
                        onPage?.invoke()
                        val p = req.url.parameters["page"]!!.toInt(); val size = req.url.parameters["size"]!!.toInt()
                        val sourceItems = ContractJson.parseToJsonElement(CanonicalFixtures.files.getValue("catalog-page.json")).jsonObject["items"]!!.jsonArray
                        val pageItems = server.drop(p*size).take(size).map { e -> sourceItems.first { it.jsonObject["id"]!!.jsonPrimitive.content == e.id } }
                        respond(buildJsonObject {
                            put("items", JsonArray(pageItems)); put("page", p); put("size", size)
                            put("totalElements", server.size); put("totalPages", (server.size + size - 1)/size)
                            put("hasNext", (p+1)*size < server.size); put("asOf", at)
                        }.toString(), headers = jsonHeaders)
                    }
                    req.method == HttpMethod.Put -> {
                        val id = path.substringAfterLast('/'); puts += id
                        if(id == failPut) respond("", HttpStatusCode.ServiceUnavailable)
                        else { if(server.none { it.id == id }) server += events.first { it.id == id }; respond("", HttpStatusCode.NoContent) }
                    }
                    req.method == HttpMethod.Delete -> {
                        server.removeAll { it.id == path.substringAfterLast('/') }; respond("", HttpStatusCode.NoContent)
                    }
                    else -> error("Unexpected path")
                }
            }
        }, source, tokenStore) { Instant.parse(at) }
        val auth = AuthStore(client, scope.backgroundScope)
        val guestDisk = GuestDisk()
        val public = StoreTestRepository()
        lateinit var guest: GuestSavedRepository
        lateinit var store: AccountSavedStore
        suspend fun start() {
            guest = GuestSavedRepository.open(guestDisk, GuestStoreScope.Remote(source.baseUrl))
            auth.start(); scope.runCurrent()
            store = AccountSavedStore(auth, client, public, guest, AccountCache(source.baseUrl, cacheDisk), scope.backgroundScope, pageSize = 1)
            scope.runCurrent()
        }
    }
    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun allPagesCommitAtomicallyWithoutTouchingGuest() = runTest {
        val s = Setup(this); s.server = events.toMutableList()
        try {
            s.start(); val state = s.store.state.value
            assertEquals(SavedMode.ACCOUNT, state.mode)
            assertEquals(events.map { it.id }.toSet(), state.entries.map { it.key.id }.toSet())
            assertNotNull(state.cachedAt); assertNull(state.error)
            assertTrue(s.guest.entries.value.isEmpty())
            val before = s.guestDisk.value
            s.store.change(events[0], remove = true); runCurrent()
            assertFalse(s.store.state.value.entries.any { it.key.id == events[0].id })
            assertEquals(before, s.guestDisk.value)
        } finally { s.client.close() }
    }

    @Test fun offlineRefreshRetainsCompleteCopyAndDoesNotClaimFreshSuccess() = runTest {
        val s = Setup(this)
        try {
            s.start(); val before = s.store.state.value.entries; val bytes = s.cacheDisk.value
            s.offline = true; s.store.refresh(); runCurrent()
            assertEquals(before, s.store.state.value.entries)
            assertNotNull(s.store.state.value.error); assertEquals(bytes, s.cacheDisk.value)
        } finally { s.client.close() }
    }

    @Test fun partialImportRecordsSuccessAndRetrySkipsItWithoutDeletingGuest() = runTest {
        val s = Setup(this)
        try {
            s.start()
            s.guest.save(StoreTestFixtures.detail(events[0].id)); s.guest.save(StoreTestFixtures.detail(events[1].id))
            val before = s.guestDisk.value
            val captured = s.guest.entries.value.reversed() // first item succeeds, second fails
            s.failPut = events[1].id
            s.store.importGuest(captured); runCurrent()
            assertEquals(1, s.store.state.value.imported.size); assertNotNull(s.store.state.value.error)
            assertEquals(before, s.guestDisk.value)
            s.failPut = null; s.store.importGuest(captured); runCurrent()
            assertEquals(2, s.store.state.value.imported.size)
            assertEquals(1, s.puts.count { it == events[0].id })
            assertEquals(before, s.guestDisk.value)
        } finally { s.client.close() }
    }

    @Test fun logoutClearsPrivateCacheAndRejectsLateRefreshResult() = runTest {
        val s = Setup(this)
        try {
            s.start(); val before = s.guestDisk.value
            val gate = CompletableDeferred<Unit>()
            s.public.onDetail = { withContext(NonCancellable) { gate.await() }; StoreTestFixtures.detail(it) }
            s.store.refresh(); runCurrent()
            s.auth.logout(); runCurrent()
            assertTrue(s.store.state.value.entries.isEmpty())
            gate.complete(Unit); runCurrent()
            assertEquals(SavedMode.GUEST, s.store.state.value.mode)
            assertNull(s.cacheDisk.value); assertEquals(before, s.guestDisk.value)
        } finally { s.client.close() }
    }

    @Test fun failedClearBlocksPrivateAccessUntilExplicitRetry() = runTest {
        val s = Setup(this)
        try {
            s.start(); s.cacheDisk.failClear = true
            s.auth.logout(); runCurrent()
            assertEquals(SavedMode.BLOCKED, s.store.state.value.mode)
            assertTrue(s.store.state.value.entries.isEmpty()); assertNotNull(s.cacheDisk.value)
            s.cacheDisk.failClear = false; s.store.retryCleanup(); runCurrent()
            assertEquals(SavedMode.GUEST, s.store.state.value.mode); assertNull(s.cacheDisk.value)
        } finally { s.client.close() }
    }

    @Test fun coldOfflineRestoreShowsMatchingCacheReadOnly() = runTest {
        val disk = CacheDisk(); val first = Setup(this, disk)
        try { first.start() } finally { first.client.close() }
        val second = Setup(this, disk).apply { offline = true }
        try {
            second.start()
            assertEquals(SavedMode.ACCOUNT, second.store.state.value.mode)
            assertEquals(events[0].id, second.store.state.value.entries.single().key.id)
            assertFalse(second.store.state.value.writable)
            second.store.change(events[1], false); runCurrent()
            assertTrue(second.puts.isEmpty())
        } finally { second.client.close() }
    }

    @Test fun sameTotalPaginationRaceKeepsPreviousCompleteCache() = runTest {
        val s = Setup(this).apply { server = events.take(2).toMutableList() }
        try {
            s.start(); val before = s.cacheDisk.value; val entries = s.store.state.value.entries
            var pageCalls = 0
            s.onPage = { if(++pageCalls == 2) s.server = events.drop(1).take(2).toMutableList() }
            s.store.refresh(); runCurrent()
            assertEquals(before, s.cacheDisk.value)
            assertEquals(entries, s.store.state.value.entries)
            assertNotNull(s.store.state.value.error)
        } finally { s.client.close() }
    }
}
