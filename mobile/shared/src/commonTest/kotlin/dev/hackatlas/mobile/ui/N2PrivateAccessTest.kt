package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.*
import dev.hackatlas.mobile.data.applications.ApplicationCache
import dev.hackatlas.mobile.data.applications.ApplicationCacheException
import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.data.saved.*
import dev.hackatlas.mobile.domain.CatalogSource
import dev.hackatlas.mobile.domain.AccountAccessMode
import dev.hackatlas.mobile.domain.GuestStoreScope
import dev.hackatlas.mobile.ui.applications.ApplicationStore
import dev.hackatlas.mobile.ui.applications.visibleFor
import dev.hackatlas.mobile.ui.auth.AuthStore
import dev.hackatlas.mobile.ui.auth.EmailVerificationStore
import dev.hackatlas.mobile.ui.profile.ProfileEditStore
import dev.hackatlas.mobile.ui.saved.AccountSavedStore
import dev.hackatlas.mobile.ui.saved.visibleFor
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.Instant
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class N2PrivateAccessTest {
    private val source = CatalogSource.Remote("https://private.example.org/api/v1/")
    private val user = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json"))
    private val event = StoreTestFixtures.events.first()
    private val at = Instant.parse("2026-11-08T12:00:00Z")
    private val expires = "2026-11-08T12:00:10Z"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val oldBearer = "test-session-one-" + "a".repeat(32)
    private val newBearer = "test-session-two-" + "b".repeat(32)

    @Test fun confirmingEmailRestoresBothCopiesOnlyAfterMeWithTheSameBearerAndExpiry() = runTest {
        val disk = Disk()
        val seed = Process(this, disk)
        seed.start()
        assertNotNull(disk.saved.text)
        assertNotNull(disk.applications.text)
        seed.close()
        val process = Process(this, disk)
        try {
            process.pending = true
            process.start()
            val identity = assertNotNull(process.client.localIdentity.value)
            val before = disk.session
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            assertTrue(process.verification.open(identity))
            process.verification.requestCode()
            runCurrent()
            val me = CompletableDeferred<Unit>()
            process.waitForOldMe = me
            process.verification.changeCode("01234567")
            process.verification.confirmCode()
            runCurrent()
            assertTrue(process.requests.contains("POST /api/v1/auth/email-verification/confirm"))
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            assertFalse(process.auth.state.value.canReadPrivate)
            me.complete(Unit)
            runCurrent()
            assertEquals(event.id, process.saved.state.value.entries.single().key.id)
            assertEquals(event.id, process.applications.state.value.marks.single().hackathonId)
            assertEquals(identity, process.client.localIdentity.value)
            assertSame(before, disk.session)
        } finally { process.close() }
    }

    @Test fun coldOfflineHidesBothCopiesUntilMeAndWarmOfflineKeepsReadOnlyUntilExpiry() = runTest {
        val disk = Disk()
        val original = Process(this, disk)
        original.start()
        assertEquals(event.id, original.saved.state.value.entries.single().key.id)
        assertEquals(event.id, original.applications.state.value.marks.single().hackathonId)
        original.close()
        val savedBytes = disk.saved.text
        val markBytes = disk.applications.text
        val resumed = Process(this, disk)
        try {
            resumed.offline = true
            resumed.start()
            assertTrue(resumed.saved.state.value.entries.isEmpty(), "cold saved cache must stay hidden")
            assertTrue(resumed.applications.state.value.marks.isEmpty(), "cold application cache must stay hidden")
            assertEquals(savedBytes, disk.saved.text)
            assertEquals(markBytes, disk.applications.text)
            assertFalse(resumed.editor.open())
            resumed.offline = false
            val lists = CompletableDeferred<Unit>()
            resumed.waitForLists = lists
            resumed.auth.refresh()
            runCurrent()
            assertEquals(event.id, resumed.saved.state.value.entries.single().key.id)
            assertEquals(event.id, resumed.applications.state.value.marks.single().hackathonId)
            assertEquals(savedBytes, disk.saved.text)
            assertEquals(markBytes, disk.applications.text)
            lists.complete(Unit)
            runCurrent()
            resumed.offline = true
            resumed.saved.refresh()
            runCurrent()
            assertEquals(event.id, resumed.saved.state.value.entries.single().key.id)
            assertEquals(event.id, resumed.applications.state.value.marks.single().hackathonId)
            assertFalse(resumed.saved.state.value.writable)
            assertFalse(resumed.applications.state.value.writable)
            resumed.saved.change(event, remove = true)
            resumed.applications.change(event.id, remove = true)
            runCurrent()
            assertEquals(0, resumed.requests.count { it.startsWith("DELETE") })
            resumed.offline = false
            val offset = resumed.requests.size
            resumed.applications.refresh()
            runCurrent()
            assertEquals("GET /api/v1/me", resumed.requests[offset])
            assertTrue(resumed.saved.state.value.writable)
            assertTrue(resumed.applications.state.value.writable)
            resumed.offline = true
            resumed.auth.refresh()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(resumed.saved.state.value.entries.isEmpty())
            assertTrue(resumed.applications.state.value.marks.isEmpty())
            assertNull(disk.session)
            assertNull(disk.saved.text)
            assertNull(disk.applications.text)
        } finally { resumed.close() }
    }

    @Test fun late200And204And401CannotChangeReplacementSessionWithSameOwnerAndExpiry() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        try {
            process.start()
            val oldIdentity = process.client.localIdentity.value
            val me = CompletableDeferred<Unit>()
            val mark = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Unit>()
            process.waitForOldMe = me
            process.waitForOldMark = mark
            process.waitForOldSaved = saved
            val oldProfile = async { runCatching { process.client.profile() } }
            val oldFailure = async { runCatching { process.client.removeApplication(user.id, event.id) } }
            val oldSuccess = async { runCatching { process.client.unsave(user.id, event.id) } }
            runCurrent()
            assertEquals(2, process.requests.count { it.startsWith("DELETE") })
            val replacement = async { process.client.login("user@example.org", "example-password") }
            runCurrent()
            assertTrue(replacement.isCompleted, "old protected requests must not block a new login")
            assertNotEquals(oldIdentity, process.client.localIdentity.value)
            val current = process.client.localIdentity.value
            assertEquals(oldIdentity?.userId, current?.userId)
            assertEquals(oldIdentity?.expiresAt, current?.expiresAt)
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            process.auth.refresh()
            runCurrent()
            val newSavedBytes = assertNotNull(disk.saved.text)
            val newApplicationBytes = assertNotNull(disk.applications.text)
            val newSaved = process.saved.state.value
            val newApplications = process.applications.state.value
            me.complete(Unit)
            mark.complete(Unit)
            saved.complete(Unit)
            runCurrent()
            assertTrue(oldProfile.await().isFailure)
            assertTrue(oldFailure.await().isFailure)
            assertTrue(oldSuccess.await().isFailure)
            assertEquals(current, process.client.localIdentity.value)
            assertEquals("New profile", (process.client.state.value as AuthState.Authenticated).user.displayName)
            assertEquals(newBearer, disk.session?.accessToken)
            assertEquals(newSaved, process.saved.state.value)
            assertEquals(newApplications, process.applications.state.value)
            assertEquals(newSavedBytes, disk.saved.text)
            assertEquals(newApplicationBytes, disk.applications.text)
        } finally { process.close() }
    }

    @Test fun guarded403HidesBothCopiesAndRejectsLateSuccessWithinTheSameGeneration() = runTest {
        for ((method, path) in listOf("GET" to "/me/saved", "GET" to "/me/applications", "PATCH" to "/me")) {
            val disk = Disk()
            val process = Process(this, disk)
            try {
                process.start()
                assertTrue(process.editor.open())
                val oldSaved = process.saved.state.value
                val oldApplications = process.applications.state.value
                val identity = process.client.localIdentity.value
                val savedBytes = disk.saved.text
                val markBytes = disk.applications.text
                val gate = CompletableDeferred<Unit>()
                process.waitForOldSaved = gate
                val lateSuccess = async { runCatching { process.client.unsave(user.id, event.id) } }
                runCurrent()
                process.deny = method to path
                val failure = assertFailsWith<AuthException> {
                    when (path) {
                        "/me/saved" -> process.client.listSaved(user.id)
                        "/me/applications" -> process.client.listApplications(user.id)
                        else -> process.client.updateProfile(user.id, buildJsonObject { put("displayName", "Attempted edit") })
                    }
                }
                assertEquals(N2HttpAction.EMAIL_NOT_VERIFIED, assertIs<AuthError.Http>(failure.error).n2Action)
                runCurrent()
                assertEquals(identity, process.client.localIdentity.value)
                assertTrue(process.saved.state.value.entries.isEmpty())
                assertTrue(process.applications.state.value.marks.isEmpty())
                assertTrue(oldSaved.visibleFor(process.auth.state.value).entries.isEmpty())
                assertTrue(oldApplications.visibleFor(process.auth.state.value).marks.isEmpty())
                assertNull(process.editor.state.value.draft)
                gate.complete(Unit)
                assertTrue(lateSuccess.await().isFailure)
                assertEquals(savedBytes, disk.saved.text)
                assertEquals(markBytes, disk.applications.text)
            } finally { process.close() }
        }
    }

    @Test fun offlineLogoutWithFailedCleanupStaysClosedAcrossProcessRestartUntilRetry() = runTest {
        val disk = Disk()
        val first = Process(this, disk)
        first.start()
        first.guest.save(StoreTestFixtures.detail(event.id))
        val guest = disk.guest
        try {
            disk.saved.failClear = true
            first.offline = true
            first.auth.logout()
            runCurrent()
            assertEquals(AuthState.StorageUnavailable, first.client.state.value)
            assertNull(first.client.localIdentity.value)
            assertTrue(first.saved.state.value.entries.isEmpty())
            assertTrue(first.applications.state.value.marks.isEmpty())
            assertNotEquals(oldBearer, disk.session?.accessToken)
            assertNotNull(disk.saved.text)
            assertNull(disk.applications.text, "both cache deletions must be attempted")
        } finally { first.close() }
        val resumed = Process(this, disk)
        try {
            resumed.start()
            assertEquals(AuthState.StorageUnavailable, resumed.client.state.value)
            assertTrue(resumed.requests.none { it == "GET /api/v1/me" })
            assertTrue(resumed.saved.state.value.entries.isEmpty())
            assertTrue(resumed.applications.state.value.marks.isEmpty())
            disk.saved.failClear = false
            resumed.saved.retryCleanup()
            runCurrent()
            assertEquals(AuthState.SignInRequired, resumed.client.state.value)
            assertNull(disk.session)
            assertNull(disk.saved.text)
            assertNull(disk.applications.text)
            assertEquals(guest, disk.guest)
        } finally { resumed.close() }
    }

    @Test fun pendingReplacementPreservesBytesButVerifiedReplacementCannotReuseOldGeneration() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        try {
            process.start()
            val oldIdentity = process.client.localIdentity.value
            val oldSaved = disk.saved.text
            val oldMarks = disk.applications.text
            process.pending = true
            process.client.login("user@example.org", "example-password")
            runCurrent()
            assertNotEquals(oldIdentity, process.client.localIdentity.value)
            assertEquals(oldSaved, disk.saved.text)
            assertEquals(oldMarks, disk.applications.text)
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            process.pending = false
            val lists = CompletableDeferred<Unit>()
            process.waitForLists = lists
            process.auth.refresh()
            runCurrent()
            assertTrue(process.saved.state.value.entries.isEmpty(), "old generation cannot populate new UI")
            assertTrue(process.applications.state.value.marks.isEmpty())
            lists.complete(Unit)
            runCurrent()
            assertEquals(event.id, process.saved.state.value.entries.single().key.id)
            assertEquals(event.id, process.applications.state.value.marks.single().hackathonId)
            assertNotEquals(oldSaved, disk.saved.text)
            assertNotEquals(oldMarks, disk.applications.text)
        } finally { process.close() }
    }

    @Test fun requiredModeRejectsMissingOrDemoSource() {
        for (source in listOf(null, CatalogSource.BundledDemo)) {
            assertFailsWith<IllegalArgumentException> {
                CatalogRuntime(source, { error("Invalid configuration must be rejected before opening storage") },
                    accessMode = AccountAccessMode.N2_REQUIRED)
            }
        }
    }

    @Test fun failedLoginKeepsBothCopiesAndRegistrationGetsANewGeneration() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        try {
            process.start()
            val current = process.client.localIdentity.value
            val oldSaved = disk.saved.text
            val oldMarks = disk.applications.text
            process.failLogin = true
            val failure = assertFailsWith<AuthException> { process.client.login("user@example.org", "invalid-password") }
            assertEquals(N2HttpAction.INVALID_CREDENTIALS, assertIs<AuthError.Http>(failure.error).n2Action)
            assertEquals(current, process.client.localIdentity.value)
            assertEquals(oldSaved, disk.saved.text)
            assertEquals(oldMarks, disk.applications.text)
            assertTrue(process.saved.state.value.writable)
            assertTrue(process.applications.state.value.writable)
            process.pending = true
            process.client.register("user@example.org", "example-password", "Example")
            runCurrent()
            assertNotEquals(current?.generation, process.client.localIdentity.value?.generation)
            assertEquals(oldSaved, disk.saved.text)
            assertEquals(oldMarks, disk.applications.text)
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
        } finally { process.close() }
    }

    @Test fun switchingOwnerClearsBothPrivateFilesBeforeNewAccountBecomesVisible() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        try {
            process.start()
            process.guest.save(StoreTestFixtures.detail(event.id))
            val guest = disk.guest
            process.newUserId = "6cae09a9-7d06-4109-807f-63b3c9747c29"
            process.client.login("other@example.org", "example-password")
            runCurrent()
            assertEquals(process.newUserId, disk.session?.userId)
            assertNull(disk.saved.text)
            assertNull(disk.applications.text)
            assertEquals(guest, disk.guest)
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            process.auth.refresh()
            runCurrent()
            assertEquals(process.newUserId, process.saved.state.value.ownerId)
            assertEquals(process.newUserId, process.applications.state.value.ownerId)
        } finally { process.close() }
    }

    @Test fun current401ClearsBearerAndBothCopiesWithoutChangingGuest() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        try {
            process.start()
            process.guest.save(StoreTestFixtures.detail(event.id))
            val guest = disk.guest
            process.revoked = true
            process.auth.refresh()
            runCurrent()
            assertEquals(AuthState.SignInRequired, process.client.state.value)
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            assertNull(disk.session)
            assertNull(disk.saved.text)
            assertNull(disk.applications.text)
            assertEquals(guest, disk.guest)
        } finally { process.close() }
    }

    @Test fun pendingDuringDiskWritesKeepsTheLastAcceptedBytesForBothCaches() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        val gate = CompletableDeferred<Unit>()
        try {
            process.start()
            val savedBytes = disk.saved.text
            val markBytes = disk.applications.text
            disk.saved.writeGate = gate
            disk.applications.writeGate = gate
            process.emptyMembership = true
            process.saved.refresh()
            process.applications.refresh()
            runCurrent()
            assertTrue(disk.saved.writeStarted && disk.applications.writeStarted)
            process.pending = true
            process.auth.refresh()
            runCurrent()
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertEquals(savedBytes, disk.saved.text)
            assertEquals(markBytes, disk.applications.text)
        } finally {
            gate.complete(Unit)
            process.close()
        }
    }

    @Test fun scopesDoNotReuseCopiesFromAnotherSourceOrBearerFromLegacyMode() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        process.start()
        val generation = assertNotNull(disk.session?.generation)
        process.close()
        val another = CatalogSource.Remote("https://other-private.example.org/api/v1/")
        assertFailsWith<AccountCacheException> { AccountCache(another.baseUrl, disk.saved).open(user.id, generation) }
        assertFailsWith<ApplicationCacheException> { ApplicationCache(another.baseUrl, disk.applications).open(user.id, generation) }
        disk.session = StoredSession(oldBearer, expires, user.id)
        val n2 = Process(this, disk)
        try {
            n2.start()
            assertTrue(n2.requests.isEmpty())
            assertEquals(AuthState.SignInRequired, n2.client.state.value)
            assertNull(disk.session)
            assertNull(disk.saved.text)
            assertNull(disk.applications.text)
        } finally { n2.close() }
    }

    @Test fun pendingMeHidesBothCachesAndRetainsBytesGuestAndBearer() = runTest {
        val disk = Disk()
        val process = Process(this, disk)
        try {
            process.start()
            process.guest.save(StoreTestFixtures.detail(event.id))
            val guest = disk.guest
            val saved = disk.saved.text
            val applications = disk.applications.text
            process.pending = true
            process.auth.refresh()
            runCurrent()
            assertTrue(process.saved.state.value.entries.isEmpty())
            assertTrue(process.applications.state.value.marks.isEmpty())
            assertFalse(process.editor.open())
            process.saved.importGuest(process.guest.entries.value)
            process.applications.change(event.id, remove = true)
            runCurrent()
            assertEquals(0, process.requests.count { it.startsWith("DELETE") || it.startsWith("PUT") })
            assertEquals(saved, disk.saved.text)
            assertEquals(applications, disk.applications.text)
            assertEquals(guest, disk.guest)
            assertEquals(oldBearer, disk.session?.accessToken)
        } finally { process.close() }
    }

    private class CacheDisk : AccountCacheStorage {
        var text: String? = null
        var failClear = false
        var writeGate: CompletableDeferred<Unit>? = null
        var writeStarted = false
        override suspend fun read() = text
        override suspend fun write(contents: String) {
            writeGate?.let { writeStarted = true; it.await() }
            text = contents
        }
        override suspend fun clear() { check(!failClear); text = null }
    }

    private inner class Disk {
        var session: StoredSession? = StoredSession(oldBearer, expires, user.id, "d1a0c05a-6f56-4c64-831d-b15517944931")
        val saved = CacheDisk()
        val applications = CacheDisk()
        var guest: String? = null
    }

    private inner class Process(private val test: TestScope, val disk: Disk) {
        private val owner = CoroutineScope(test.backgroundScope.coroutineContext + SupervisorJob(test.backgroundScope.coroutineContext.job))
        val requests = mutableListOf<String>()
        var offline = false
        var pending = false
        var waitForOldMe: CompletableDeferred<Unit>? = null
        var waitForOldMark: CompletableDeferred<Unit>? = null
        var waitForOldSaved: CompletableDeferred<Unit>? = null
        var waitForLists: CompletableDeferred<Unit>? = null
        var deny: Pair<String, String>? = null
        var failLogin = false
        var revoked = false
        var emptyMembership = false
        var newUserId = user.id
        private val tokens = object : SecureTokenStore {
            override val apiBaseUrl = source.baseUrl
            override suspend fun read() = disk.session
            override suspend fun write(session: StoredSession) { disk.session = session }
            override suspend fun clear() { disk.session = null }
        }
        private val engine = MockEngine.create {
            dispatcher = StandardTestDispatcher(test.testScheduler)
            addHandler { request ->
                val path = request.url.encodedPath
                val bearer = request.headers[HttpHeaders.Authorization]
                requests += "${request.method.value} $path"
                if (offline) throw IOException("offline")
                if (revoked && path.endsWith("/me"))
                    return@addHandler respond(problem(401, path, "AUTH_REQUIRED"), HttpStatusCode.Unauthorized, problemHeaders)
                if (deny == (request.method.value to path.removePrefix("/api/v1")))
                    return@addHandler respond(problem(403, path, "EMAIL_NOT_VERIFIED"), HttpStatusCode.Forbidden, problemHeaders)
                if (failLogin && path.endsWith("/auth/login"))
                    return@addHandler respond(problem(401, path, "INVALID_CREDENTIALS"), HttpStatusCode.Unauthorized, problemHeaders)
                val newSession = bearer == "Bearer $newBearer" || path.contains("/auth/")
                val profile = JsonObject(ContractJson.encodeToJsonElement(user).jsonObject + mapOf(
                    "id" to JsonPrimitive(if (newSession) newUserId else user.id),
                    "displayName" to JsonPrimitive(if (newSession) "New profile" else "Old profile"),
                    "emailVerified" to JsonPrimitive(!pending),
                ))
                when {
                    path.endsWith(VerificationHarness.requestPath) -> {
                        assertEquals("Bearer $oldBearer", bearer)
                        respond(buildJsonObject {
                            put("challengeId", VerificationHarness.challengeId)
                            put("expiresAt", (at.toEpochMilliseconds() + 600_000).let(Instant::fromEpochMilliseconds).toString())
                            put("resendAfterSeconds", 60)
                        }.toString(), HttpStatusCode.Accepted, headers)
                    }
                    path.endsWith(VerificationHarness.confirmPath) -> {
                        assertEquals("Bearer $oldBearer", bearer)
                        pending = false
                        respond("", HttpStatusCode.NoContent)
                    }
                    path.endsWith("/auth/login") || path.endsWith("/auth/register") -> respond(buildJsonObject {
                        put("tokenType", "Bearer")
                        put("accessToken", newBearer)
                        put("expiresAt", expires)
                        put("user", profile)
                    }.toString(), if (path.endsWith("/register")) HttpStatusCode.Created else HttpStatusCode.OK, headers)
                    path.endsWith("/auth/logout") -> respond("", HttpStatusCode.NoContent)
                    path.endsWith("/me") -> {
                        if (bearer == "Bearer $oldBearer") waitForOldMe?.await()
                        respond(profile.toString(), headers = headers)
                    }
                    path.endsWith("/me/saved") -> {
                        waitForLists?.await()
                        val item = ContractJson.parseToJsonElement(CanonicalFixtures.files.getValue("catalog-page.json"))
                            .jsonObject.getValue("items").jsonArray.first { it.jsonObject.getValue("id").jsonPrimitive.content == event.id }
                        respond(page(request.url.parameters["size"]!!.toInt(), item.takeUnless { emptyMembership }), headers = headers)
                    }
                    path.endsWith("/me/applications") -> {
                        waitForLists?.await()
                        respond(page(request.url.parameters["size"]!!.toInt(),
                            ContractJson.encodeToJsonElement(ApplicationMark(event.id, "APPLIED", true, at.toString()))
                                .takeUnless { emptyMembership }),
                            headers = headers)
                    }
                    path.contains("/me/saved/") && request.method == HttpMethod.Delete -> {
                        if (bearer == "Bearer $oldBearer") waitForOldSaved?.await()
                        respond("", HttpStatusCode.NoContent)
                    }
                    path.contains("/me/applications/") && request.method == HttpMethod.Delete -> {
                        if (bearer == "Bearer $oldBearer") waitForOldMark?.await()
                        respond("", HttpStatusCode.Unauthorized)
                    }
                    else -> error("Unexpected path: $path")
                }
            }
        }
        private val savedCache = AccountCache(source.baseUrl, disk.saved)
        private val applicationsCache = ApplicationCache(source.baseUrl, disk.applications)
        val client = AuthSessionClient(engine, source, tokens, AuthProtocol.CLOSED_N2,
            clearPrivateCaches = { clearAccountCopies(savedCache, applicationsCache) }) {
            Instant.fromEpochMilliseconds(at.toEpochMilliseconds() + test.testScheduler.currentTime)
        }
        val auth = AuthStore(client, owner)
        val verification = EmailVerificationStore(N2AccountClient(client), client, owner) {
            Instant.fromEpochMilliseconds(at.toEpochMilliseconds() + test.testScheduler.currentTime)
        }
        val editor = ProfileEditStore(auth, client, owner)
        lateinit var guest: GuestSavedRepository
        lateinit var saved: AccountSavedStore
        lateinit var applications: ApplicationStore

        suspend fun start() {
            guest = GuestSavedRepository.open(object : GuestSnapshotStorage {
                override suspend fun read() = disk.guest
                override suspend fun write(contents: String) { disk.guest = contents }
            }, GuestStoreScope.Remote(source.baseUrl))
            saved = AccountSavedStore(auth, client, StoreTestRepository(), guest, savedCache, owner)
            applications = ApplicationStore(auth, client, applicationsCache, owner)
            auth.start()
            test.runCurrent()
        }

        fun close() { owner.cancel(); client.close() }
    }

    private val problemHeaders = headersOf(HttpHeaders.ContentType, "application/problem+json")

    private fun problem(status: Int, path: String, code: String) = buildJsonObject {
        put("type", "about:blank")
        put("title", "Request denied")
        put("status", status)
        put("detail", "Request denied in test")
        put("instance", path)
        put("code", code)
        put("requestId", "request-1")
        put("fieldErrors", JsonArray(emptyList()))
    }.toString()

    private fun page(size: Int, item: JsonElement?) = buildJsonObject {
        val items = listOfNotNull(item)
        put("items", JsonArray(items))
        put("page", 0)
        put("size", size)
        put("totalElements", items.size)
        put("totalPages", items.size)
        put("hasNext", false)
        put("asOf", at.toString())
    }.toString()
}
