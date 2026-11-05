package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.applications.*
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.data.saved.AccountCacheStorage
import dev.hackatlas.mobile.domain.CatalogSource
import dev.hackatlas.mobile.ui.applications.*
import dev.hackatlas.mobile.ui.auth.AuthStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationStoreTest {
    private val source = CatalogSource.Remote("https://applications.example.org/api/v1/")
    private val profile = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private val id = "39287368-5259-5235-a1c4-25b4e1e8421e"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val at = "2026-11-05T10:00:00Z"
    private fun mark(id: String = this.id) = ApplicationMark(id, "APPLIED", true, at)
    private class Disk : AccountCacheStorage {
        var text: String? = null;var failClear = false;var failWrite = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read() = text
        override suspend fun write(contents: String) { gate?.await();if(failWrite) error("disk full");text = contents }
        override suspend fun clear() { if(failClear) error("denied");text = null }
    }
    private inner class Setup(val test: TestScope, val disk: Disk = Disk()) {
        var offline = false;var unauthorized = false;var lostPut = false;var failProfile = false
        var profileId = profile.id
        var server = mutableListOf<ApplicationMark>()
        val requests = mutableListOf<String>()
        var onList: (suspend (Int) -> Unit)? = null
        var onPut: (suspend () -> Unit)? = null
        val tokens = object : SecureTokenStore {
            override val apiBaseUrl = source.baseUrl
            var session: StoredSession? = StoredSession("synthetic-mark-" + "m".repeat(40), "2026-12-01T00:00:00Z", profile.id)
            override suspend fun read() = session
            override suspend fun write(session: StoredSession) { this.session = session }
            override suspend fun clear() { session = null }
        }
        fun user() = profile.copy(id = profileId, stats = profile.stats.copy(appliedCount = server.size))
        val client = AuthSessionClient(MockEngine.create {
            dispatcher = StandardTestDispatcher(test.testScheduler)
            addHandler { req ->
                val path = req.url.encodedPath;requests += "${req.method.value} $path"
                if(offline || (path == "/api/v1/me" && failProfile)) throw kotlinx.io.IOException("offline")
                if(unauthorized) respond("", HttpStatusCode.Unauthorized)
                else when {
                    path == "/api/v1/me" -> respond(ContractJson.encodeToString(user()), headers = jsonHeaders)
                    path == "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                    path == "/api/v1/auth/login" -> respond(buildJsonObject {
                        put("tokenType", "Bearer");put("accessToken", "synthetic-next-" + "n".repeat(40))
                        put("expiresAt", "2026-12-01T00:00:00Z");put("user", ContractJson.encodeToJsonElement(user()))
                    }.toString(), headers = jsonHeaders)
                    path == "/api/v1/me/applications" -> {
                        val p = req.url.parameters["page"]!!.toInt();val size = req.url.parameters["size"]!!.toInt()
                        onList?.invoke(p)
                        respond(ContractJson.encodeToString(ApplicationPage(server.drop(p*size).take(size), p, size,
                            server.size, (server.size+size-1)/size, (p+1)*size < server.size, at)), headers = jsonHeaders)
                    }
                    req.method == HttpMethod.Put -> {
                        val event = path.substringAfterLast('/');onPut?.invoke()
                        val value = server.firstOrNull { it.hackathonId == event } ?: mark(event).also { server.add(0, it) }
                        if(lostPut) throw kotlinx.io.IOException("reply lost after commit")
                        respond(ContractJson.encodeToString(value), headers = jsonHeaders)
                    }
                    req.method == HttpMethod.Delete -> { server.removeAll { it.hackathonId == path.substringAfterLast('/') };respond("", HttpStatusCode.NoContent) }
                    else -> error("Unexpected API path")
                }
            }
        }, source, tokens) { Instant.parse(at) }
        val auth = AuthStore(client, test.backgroundScope)
        lateinit var store: ApplicationStore
        fun start() {
            auth.start();test.runCurrent()
            store = ApplicationStore(auth, client, ApplicationCache(source.baseUrl, disk), test.backgroundScope,
                pageSize = 1, now = { Instant.parse(at) }, onChanged = auth::refresh)
            test.runCurrent()
        }
    }
    private val jsonHeaders get() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun completePaginationIsAtomicAndInitialEmptyIsKnown() = runTest {
        val s = Setup(this);s.server += listOf(mark(),mark(other));val gate = CompletableDeferred<Unit>()
        s.onList = { if(it == 1) gate.await() }
        try {
            s.start();assertFalse(s.store.state.value.known);assertNull(s.disk.text)
            gate.complete(Unit);runCurrent()
            assertEquals(listOf(id,other), s.store.state.value.marks.map { it.hackathonId })
            assertTrue(s.store.state.value.known);assertTrue(s.store.state.value.writable)
            assertTrue(s.requests.none { it.contains("saved") })
        } finally { s.client.close() }
        val empty = Setup(this)
        try { empty.start();assertTrue(empty.store.state.value.known);assertTrue(empty.store.state.value.marks.isEmpty()) }
        finally { empty.client.close() }
    }

    @Test fun onlyConfirmedPutAndDeleteUpdateStateCacheAndServerProfile() = runTest {
        val s = Setup(this);val gate = CompletableDeferred<Unit>();s.onPut = { gate.await() }
        try {
            s.start();s.store.change(id, false);s.store.change(id, false);runCurrent()
            assertNull(s.store.state.value.markFor(id));assertTrue(s.store.state.value.syncing)
            assertEquals(1, s.requests.count { it.startsWith("PUT") })
            gate.complete(Unit);runCurrent()
            assertEquals(at, s.store.state.value.markFor(id)?.markedAt)
            assertEquals(1, (s.auth.state.value.session as AuthState.Authenticated).user.stats.appliedCount)
            assertEquals(at, ApplicationCache(source.baseUrl, s.disk).open(profile.id).marks.single().markedAt)
            s.store.change(id, false);runCurrent();assertEquals(1, s.requests.count { it.startsWith("PUT") })
            s.store.change(id, true);runCurrent();assertNull(s.store.state.value.markFor(id))
            assertTrue(s.server.isEmpty());assertTrue(ApplicationCache(source.baseUrl, s.disk).open(profile.id).marks.isEmpty())
        } finally { s.client.close() }
    }

    @Test fun lostPutResultRequiresExplicitReadbackBeforeAnotherMutation() = runTest {
        val s = Setup(this)
        try {
            s.start();s.lostPut = true;s.store.change(id, false);runCurrent()
            assertTrue(s.server.isNotEmpty());assertFalse(s.store.state.value.writable)
            assertEquals(id, s.store.state.value.uncertainId);assertNull(s.store.state.value.markFor(id))
            s.store.change(id, false);runCurrent();assertEquals(1, s.requests.count { it.startsWith("PUT") })
            s.lostPut = false;s.store.refresh();runCurrent()
            assertNull(s.store.state.value.uncertainId);assertEquals(at, s.store.state.value.markFor(id)?.markedAt)
        } finally { s.client.close() }
    }

    @Test fun confirmedMutationIsNotRolledBackWhenDiskOrProfileRefreshFails() = runTest {
        for(diskFailure in listOf(true,false)) {
            val s = Setup(this)
            try {
                s.start();s.disk.failWrite = diskFailure;s.failProfile = !diskFailure
                s.store.change(id, false);runCurrent()
                assertEquals(at, s.store.state.value.markFor(id)?.markedAt)
                assertNull(s.store.state.value.uncertainId)
                if(diskFailure) {
                    assertEquals(ApplicationFailure.Storage, s.store.state.value.error)
                    assertFalse(s.store.state.value.fromCache)
                } else assertTrue(s.store.state.value.fromCache, "Lost profile connectivity must label the last snapshot as cached")
            } finally { s.client.close() }
        }
    }

    @Test fun restartOfflineRestoresOnlyKnownPrivateCacheAndRejectsMutations() = runTest {
        val disk = Disk();val live = Setup(this,disk);live.server += mark()
        try { live.start();assertNotNull(disk.text) } finally { live.client.close() }
        val offline = Setup(this,disk);offline.offline = true
        try {
            offline.start();assertEquals(at,offline.store.state.value.markFor(id)?.markedAt)
            assertTrue(offline.store.state.value.fromCache);assertTrue(offline.store.state.value.known)
            assertFalse(offline.store.state.value.writable)
            offline.store.change(id,true);runCurrent();assertTrue(offline.requests.none { it.startsWith("DELETE") })
        } finally { offline.client.close() }
    }

    @Test fun failedPaginationNeverCommitsPartialOrFalseAbsence() = runTest {
        val s = Setup(this);s.server += listOf(mark(),mark(other))
        s.onList = { if(it == 1) s.server.removeAt(0) }
        try { s.start();assertFalse(s.store.state.value.known);assertNull(s.disk.text);assertNotNull(s.store.state.value.error) }
        finally { s.client.close() }
    }

    @Test fun secondScanDetectsStableTotalButChangedMembership() = runTest {
        val s = Setup(this);s.server += listOf(mark(),mark(other));var starts = 0
        s.onList = { if(it == 0 && ++starts == 2) s.server[1] = mark("33333333-3333-4333-8333-333333333333") }
        try { s.start();assertFalse(s.store.state.value.known);assertNull(s.disk.text) }
        finally { s.client.close() }
    }

    @Test fun logoutInvalidatesDelayedResponseAndClearsPrivateCache() = runTest {
        val s = Setup(this);s.server += mark();val gate = CompletableDeferred<Unit>()
        try {
            s.start();s.onList = { withContext(NonCancellable) { gate.await() } }
            s.store.refresh();runCurrent();s.auth.logout();runCurrent()
            assertTrue(s.store.state.value.marks.isEmpty())
            gate.complete(Unit);runCurrent()
            assertTrue(s.store.state.value.marks.isEmpty());assertNull(s.disk.text)
            assertEquals(ApplicationMode.GUEST, s.store.state.value.mode)
        } finally { gate.complete(Unit);s.client.close() }
    }

    @Test fun logoutDuringPutNeverPublishesTheOldOwnersLateResponse() = runTest {
        val s = Setup(this);val gate = CompletableDeferred<Unit>()
        s.onPut = { withContext(NonCancellable) { gate.await() } }
        try {
            s.start();s.store.change(id,false);runCurrent();s.auth.logout();runCurrent()
            assertTrue(s.store.state.value.marks.isEmpty())
            gate.complete(Unit);runCurrent()
            assertNull(s.disk.text);assertTrue(s.store.state.value.marks.isEmpty())
            assertEquals(ApplicationMode.GUEST,s.store.state.value.mode)
        } finally { gate.complete(Unit);s.client.close() }
    }

    @Test fun ownerCleanupWaitsForAlreadyStartedAtomicMutationWrite() = runTest {
        val s = Setup(this);val gate = CompletableDeferred<Unit>()
        try {
            s.start();s.disk.gate = gate;s.store.change(id,false);runCurrent()
            assertNotNull(s.store.state.value.markFor(id))
            s.auth.logout();runCurrent();assertTrue(s.store.state.value.marks.isEmpty())
            gate.complete(Unit);runCurrent();assertNull(s.disk.text)
            assertEquals(ApplicationMode.GUEST,s.store.state.value.mode)
        } finally { gate.complete(Unit);s.client.close() }
    }

    @Test fun newAccountNeverDisplaysThePreviousOwnersMarks() = runTest {
        val s = Setup(this);s.server += mark()
        try {
            s.start();s.auth.logout();runCurrent();s.profileId = other;s.server.clear()
            s.client.login("other@example.org","synthetic-password");runCurrent()
            assertEquals(other,s.store.state.value.ownerId);assertTrue(s.store.state.value.marks.isEmpty())
            assertEquals(other, ApplicationCache(source.baseUrl,s.disk).open(other).userId)
        } finally { s.client.close() }
    }

    @Test fun unauthorizedRefreshClearsCredentialAndApplicationCache() = runTest {
        val s = Setup(this);s.server += mark()
        try {
            s.start();s.unauthorized = true;s.store.refresh();runCurrent()
            assertTrue(s.store.state.value.marks.isEmpty());assertNull(s.disk.text);assertNull(s.tokens.read())
        } finally { s.client.close() }
    }

    @Test fun cleanupAndCorruptCacheFailuresBlockUntilExplicitRecovery() = runTest {
        val s = Setup(this);s.server += mark()
        try {
            s.start();s.disk.failClear = true;s.auth.logout();runCurrent()
            assertEquals(ApplicationMode.BLOCKED,s.store.state.value.mode);assertTrue(s.store.state.value.marks.isEmpty())
            s.disk.failClear = false;s.store.retryCleanup();runCurrent();assertNull(s.disk.text)
            assertEquals(ApplicationMode.GUEST,s.store.state.value.mode)
        } finally { s.client.close() }
        val corrupted = Setup(this);corrupted.disk.text = "{}"
        try {
            corrupted.start();assertEquals(ApplicationMode.BLOCKED,corrupted.store.state.value.mode)
            corrupted.store.retryCleanup();runCurrent();assertTrue(corrupted.store.state.value.known)
        } finally { corrupted.client.close() }
    }
}
