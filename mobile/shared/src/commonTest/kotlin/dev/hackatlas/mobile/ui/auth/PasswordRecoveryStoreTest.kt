package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.auth.*
import dev.hackatlas.mobile.data.GuestSnapshotStorage
import dev.hackatlas.mobile.data.saved.AccountCacheStorage
import dev.hackatlas.mobile.domain.AccountAccessMode
import dev.hackatlas.mobile.domain.CatalogSource
import dev.hackatlas.mobile.ui.CatalogRuntime
import io.ktor.http.encodedPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import kotlinx.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordRecoveryStoreTest {
    @Test fun completesSignedOutRecoveryWithoutCreatingSession() = runTest {
        val paths = mutableListOf<String>()
        fixture { path ->
            paths += path
            success(path)
        }.use { (h, store) ->
            assertTrue(store.open(" fixture@example.org "))
            store.requestCode()
            store.requestCode()
            runCurrent()
            assertEquals(RecoveryStep.CODE, store.state.value.step)
            store.changeCode("01234567")
            store.onBackground()
            assertEquals(RecoveryStep.CODE, store.state.value.step)
            assertEquals("", store.state.value.code)
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(RecoveryStep.PASSWORD, store.state.value.step)
            assertEquals("", store.state.value.code)
            store.changePassword("🔐".repeat(6))
            assertFalse(store.state.value.canReset)
            val password = "  ${"🔐".repeat(129)}  "
            store.changePassword(password)
            assertTrue(store.state.value.canReset)
            assertTrue(validateAuthForm(AuthFormMode.LOGIN, "fixture@example.org", password, "").isEmpty())
            assertFalse(store.state.value.toString().contains(password))
            store.resetPassword()
            runCurrent()
            assertEquals(RecoveryStep.COMPLETE, store.state.value.step)
            assertEquals("", store.state.value.password)
            assertNull(h.disk.session)
            assertNull(h.session.localIdentity.value)
            assertEquals(listOf(RecoveryFixtures.request, RecoveryFixtures.verify, RecoveryFixtures.confirm), paths)
        }
    }

    @Test fun lateVerifyAfterBackgroundCannotRestoreGrant() = runTest {
        val release = CompletableDeferred<Unit>()
        var verifyCalls = 0
        fixture { path ->
            if (path == RecoveryFixtures.verify) {
                verifyCalls++
                release.await()
            }
            success(path)
        }.use { (_, store) ->
            assertTrue(store.open("fixture@example.org"))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(1, verifyCalls)
            store.onBackground()
            release.complete(Unit)
            runCurrent()
            assertEquals(RecoveryStep.EMAIL, store.state.value.step)
            assertEquals("", store.state.value.code)
            store.changePassword("preview password")
            assertEquals("", store.state.value.password)
            store.close()
            assertFalse(store.state.value.open)
        }
    }

    @Test fun lostVerifyRequiresNewCodeAndLostConfirmOffersLoginWithoutRetry() = runTest {
        var lost = RecoveryFixtures.verify
        val paths = mutableListOf<String>()
        fixture { path ->
            paths += path
            if (path == lost) throw IOException("response lost")
            success(path)
        }.use { (h, store) ->
            assertTrue(store.open("fixture@example.org"))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(RecoveryStep.EMAIL, store.state.value.step)
            store.verifyCode()
            store.requestCode()
            runCurrent()
            assertEquals(2, paths.size)
            advanceTimeBy(60_000)
            runCurrent()
            lost = RecoveryFixtures.confirm
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            store.changePassword("preview password")
            store.resetPassword()
            runCurrent()
            assertEquals(RecoveryStep.UNCERTAIN, store.state.value.step)
            assertEquals("", store.state.value.password)
            store.resetPassword()
            runCurrent()
            assertEquals(1, paths.count { it == RecoveryFixtures.confirm })
            assertNull(h.disk.session)
        }
    }

    @Test fun invalidProofAndRateLimitKeepOnlyTheRetryableStage() = runTest {
        val quota = N2HttpFixtures.response(VerificationHarness.requestPath, 429)
        var verifications = 0
        fixture { path ->
            when (path) {
                RecoveryFixtures.verify -> when (++verifications) {
                    1 -> quota
                    2 -> N2HttpFixtures.response(path, 400) { it.code == "EMAIL_ACTION_INVALID" }
                    else -> RecoveryFixtures.grant
                }
                RecoveryFixtures.confirm -> N2HttpFixtures.response(path, 400) { it.code == "EMAIL_ACTION_INVALID" }
                else -> success(path)
            }
        }.use { (_, store) ->
            assertTrue(store.open("fixture@example.org"))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(quota.retryAfter.toInt(), store.state.value.retrySeconds)
            assertEquals("", store.state.value.code)
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(1, verifications)
            advanceTimeBy(quota.retryAfter.toLong() * 1000)
            runCurrent()
            store.verifyCode()
            runCurrent()
            assertEquals(RecoveryStep.CODE, store.state.value.step)
            assertEquals("", store.state.value.code)
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            store.changePassword("preview password")
            store.resetPassword()
            runCurrent()
            assertEquals(RecoveryStep.EMAIL, store.state.value.step)
            assertEquals("", store.state.value.password)
        }
    }

    @Test fun activeAccountCannotOpenRecoveryAndBackgroundClearsExistingGrant() = runTest {
        fixture(signedIn = true) { path -> success(path) }.use { (_, store) ->
            assertFalse(store.open("fixture@example.org"))
        }
        fixture { path -> success(path) }.use { (_, store) ->
            assertTrue(store.open("fixture@example.org"))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(RecoveryStep.PASSWORD, store.state.value.step)
            store.changePassword("preview password")
            store.onBackground()
            assertEquals(RecoveryStep.EMAIL, store.state.value.step)
            assertEquals("", store.state.value.password)
            assertFalse(store.state.value.canReset)
        }
    }

    @Test fun serviceUnavailableAllowsManualResendAndRequiresNewCodeAfterUncertainVerify() = runTest {
        val unavailable = N2HttpFixtures.response(RecoveryFixtures.request, 503)
        val calls = mutableMapOf<String, Int>()
        fixture { path ->
            val count = (calls[path] ?: 0) + 1
            calls[path] = count
            if (count == 1) unavailable else success(path)
        }.use { (_, store) ->
            assertTrue(store.open("fixture@example.org"))
            store.requestCode()
            runCurrent()
            assertEquals(60, store.state.value.resendSeconds)
            assertFalse(store.state.value.canRequest)
            assertEquals(N2HttpAction.RETRY_MANUALLY, assertIs<AuthError.Http>(store.state.value.error).n2Action)
            assertEquals(1, calls[RecoveryFixtures.request])
            advanceTimeBy(60_000)
            runCurrent()
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(RecoveryStep.EMAIL, store.state.value.step)
            assertEquals(RecoveryNotice.NEW_CODE_REQUIRED, store.state.value.notice)
            assertEquals("", store.state.value.code)
            assertEquals(N2HttpAction.RETRY_MANUALLY, assertIs<AuthError.Http>(store.state.value.error).n2Action)
            assertEquals(1, calls[RecoveryFixtures.verify])
        }
    }

    @Test fun confirmRetryAfterAlsoBlocksVerifyInAReopenedFlow() = runTest {
        val recorded = N2HttpFixtures.response(VerificationHarness.requestPath, 429)
        val quota = recorded.copy(headers = recorded.headers.mapValues { (name, value) ->
            if (name.equals("Retry-After", ignoreCase = true)) "120" else value
        })
        var verifies = 0
        fixture { path ->
            if (path == RecoveryFixtures.verify) verifies++
            if (path == RecoveryFixtures.confirm) quota else success(path)
        }.use { (_, store) ->
            assertTrue(store.open("fixture@example.org"))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            store.changePassword("preview password")
            store.resetPassword()
            runCurrent()
            assertEquals(120, store.state.value.retrySeconds)
            store.close()
            assertTrue(store.open("fixture@example.org"))
            advanceTimeBy(60_000)
            runCurrent()
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.verifyCode()
            runCurrent()
            assertEquals(60, store.state.value.retrySeconds)
            assertEquals(1, verifies)
        }
    }

    @Test fun runtimeForegroundKeepsSignedOutRecoveryOpenWithoutRestoringPrivateStores() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var secureReads = 0
        val runtime = CatalogRuntime(
            CatalogSource.Remote("https://recovery.example.org/api/v1/"),
            {
                object : GuestSnapshotStorage {
                    override suspend fun read(): String? = null
                    override suspend fun write(contents: String) = Unit
                }
            },
            catalogEnabled = false,
            secureStoreFactory = { source ->
                object : SecureTokenStore {
                    override val apiBaseUrl = source.baseUrl
                    override suspend fun read(): StoredSession? {
                        secureReads++
                        delay(1)
                        return null
                    }
                    override suspend fun write(session: StoredSession) = error("Unexpected session write")
                    override suspend fun clear() = Unit
                }
            },
            accountCacheFactory = { emptyAccountCache() },
            applicationCacheFactory = { emptyAccountCache() },
            accessMode = AccountAccessMode.N2_REQUIRED,
        )
        try {
            runtime.state.load()
            advanceTimeBy(2)
            runCurrent()
            val recovery = assertNotNull(runtime.state.passwordRecovery)
            assertTrue(recovery.open("fixture@example.org"))
            runtime.onBackground()
            runtime.onForeground()
            advanceTimeBy(2)
            runCurrent()
            assertEquals(1, secureReads)
            assertEquals(RecoveryStep.EMAIL, recovery.state.value.step)
            assertEquals("fixture@example.org", recovery.state.value.email)
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    private fun emptyAccountCache() = object : AccountCacheStorage {
        override suspend fun read(): String? = null
        override suspend fun write(contents: String) = Unit
        override suspend fun clear() = Unit
    }

    private fun success(path: String) = when (path) {
        "/me" -> VerificationHarness.pending
        RecoveryFixtures.request -> RecoveryFixtures.challenge
        RecoveryFixtures.verify -> RecoveryFixtures.grant
        RecoveryFixtures.confirm -> N2HttpFixtures.response(path, 204)
        else -> error("Unexpected path")
    }

    private fun TestScope.fixture(
        signedIn: Boolean = false,
        response: suspend (String) -> N2CapturedResponse,
    ): Fixture {
        val clock = { RecoveryFixtures.now + testScheduler.currentTime.milliseconds }
        val harness = VerificationHarness(clock, StandardTestDispatcher(testScheduler)) {
            respondWith(response(it.url.encodedPath.removePrefix("/api/v1")))
        }
        if (!signedIn) harness.disk.session = null
        val auth = AuthStore(harness.session, backgroundScope)
        auth.start()
        runCurrent()
        return Fixture(harness, PasswordRecoveryStore(harness.client, auth, backgroundScope, clock))
    }

    private data class Fixture(val harness: VerificationHarness, val store: PasswordRecoveryStore) : AutoCloseable {
        override fun close() = harness.session.close()
    }
}
