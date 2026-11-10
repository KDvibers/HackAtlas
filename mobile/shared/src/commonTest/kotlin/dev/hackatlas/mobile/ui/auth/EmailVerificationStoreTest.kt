package dev.hackatlas.mobile.ui.auth

import dev.hackatlas.mobile.data.auth.*
import io.ktor.http.encodedPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationStoreTest {
    @Test fun alreadyVerifiedRequestChecksMeBeforeGrantingAccess() = runTest {
        var profileCalls = 0
        val release = CompletableDeferred<Unit>()
        fixture { path ->
            when (path) {
                VerificationHarness.requestPath -> N2HttpFixtures.response(path, 204)
                else -> if (++profileCalls == 1) VerificationHarness.pending else {
                    release.await()
                    VerificationHarness.verified
                }
            }
        }.use { (h, store) ->
            h.session.restore()
            val identity = h.session.localIdentity.value!!
            assertTrue(store.open(identity))
            store.requestCode()
            runCurrent()
            assertEquals(2, profileCalls)
            assertFalse(h.session.currentPrivateAccess()!!.canRead)
            release.complete(Unit)
            runCurrent()
            assertTrue(h.session.currentPrivateAccess()!!.canRead)
            assertEquals(identity, h.session.localIdentity.value)
        }
    }

    @Test fun lostRequestWaitsAcrossReopeningAndChallengeExpiresAndClearsCode() = runTest {
        var requests = 0
        fixture { path ->
            when (path) {
                VerificationHarness.requestPath -> {
                    if (++requests == 1) throw IOException("response lost")
                    VerificationHarness.accepted
                }
                else -> VerificationHarness.pending
            }
        }.use { (h, store) ->
            h.session.restore()
            val identity = h.session.localIdentity.value!!
            assertTrue(store.open(identity))
            store.requestCode()
            runCurrent()
            assertEquals(60, store.state.value.resendSeconds)
            assertNull(store.state.value.challenge)
            store.close()
            assertTrue(store.open(identity))
            store.requestCode()
            runCurrent()
            assertEquals(1, requests)
            advanceTimeBy(60_000)
            runCurrent()
            store.requestCode()
            runCurrent()
            assertEquals(2, requests)
            assertNotNull(store.state.value.challenge)
            store.changeCode("01234567")
            assertFalse(store.state.value.toString().contains("01234567"))
            store.onBackground()
            assertEquals("", store.state.value.code)
            assertNotNull(store.state.value.challenge)
            store.changeCode("01234567")
            advanceTimeBy(60_000)
            runCurrent()
            assertNull(store.state.value.challenge)
            assertEquals("", store.state.value.code)
            assertFalse(store.state.value.canConfirm)
        }
    }

    @Test fun lostConfirmChecksProfileAndNeverReplaysProof() = runTest {
        var confirmed = false
        var confirms = 0
        var checks = 0
        fixture { path ->
            when (path) {
                VerificationHarness.requestPath -> VerificationHarness.accepted
                VerificationHarness.confirmPath -> {
                    confirms++
                    confirmed = true
                    throw IOException("response lost after commit")
                }
                else -> when {
                    !confirmed -> VerificationHarness.pending
                    ++checks == 1 -> throw IOException("still offline")
                    else -> VerificationHarness.verified
                }
            }
        }.use { (h, store) ->
            h.session.restore()
            val identity = h.session.localIdentity.value!!
            assertTrue(store.open(identity))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.confirmCode()
            store.confirmCode()
            runCurrent()
            assertEquals(1, confirms)
            assertEquals(1, checks)
            assertTrue(store.state.value.needsProfileCheck)
            assertFalse(h.session.currentPrivateAccess()!!.canRead)
            store.close()
            assertTrue(store.open(identity))
            store.requestCode()
            store.confirmCode()
            runCurrent()
            assertEquals(1, confirms)
            store.checkProfile()
            runCurrent()
            assertEquals(2, checks)
            assertTrue(h.session.currentPrivateAccess()!!.canRead)
            assertEquals(identity, h.session.localIdentity.value)
            assertNull(store.state.value.challenge)
            assertEquals("", store.state.value.code)
        }
    }

    @Test fun invalidCodeClearsInputAndRateLimitDisablesResend() = runTest {
        var requests = 0
        val quota = N2HttpFixtures.response(VerificationHarness.requestPath, 429)
        fixture { path ->
            when (path) {
                VerificationHarness.requestPath -> if (++requests == 1) VerificationHarness.accepted else quota
                VerificationHarness.confirmPath -> N2HttpFixtures.response(path, 400) { it.code == "EMAIL_ACTION_INVALID" }
                else -> VerificationHarness.pending
            }
        }.use { (h, store) ->
            h.session.restore()
            assertTrue(store.open(h.session.localIdentity.value!!))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.confirmCode()
            runCurrent()
            assertEquals("", store.state.value.code)
            assertNotNull(store.state.value.challenge)
            assertEquals(N2HttpAction.INVALID_EMAIL_ACTION, assertIs<AuthError.Http>(store.state.value.error).n2Action)
            advanceTimeBy(60_000)
            runCurrent()
            store.requestCode()
            runCurrent()
            assertEquals(quota.retryAfter.toInt(), store.state.value.resendSeconds)
            store.requestCode()
            runCurrent()
            assertEquals(2, requests)
        }
    }

    @Test fun acknowledgedConfirmCannotReuseProofEvenIfProfileStillReportsPending() = runTest {
        var confirms = 0
        fixture { path ->
            when (path) {
                VerificationHarness.requestPath -> VerificationHarness.accepted
                VerificationHarness.confirmPath -> {
                    confirms++
                    N2HttpFixtures.response(path, 204)
                }
                else -> VerificationHarness.pending
            }
        }.use { (h, store) ->
            h.session.restore()
            assertTrue(store.open(h.session.localIdentity.value!!))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.confirmCode()
            runCurrent()
            assertTrue(store.state.value.profileStillPending)
            assertNull(store.state.value.challenge)
            store.changeCode("01234567")
            store.confirmCode()
            runCurrent()
            assertEquals(1, confirms)
            assertFalse(h.session.currentPrivateAccess()!!.canRead)
        }
    }

    @Test fun incompatibleEndpointStopsVerificationForTheSameSessionAcrossReopening() = runTest {
        for (confirmUnavailable in listOf(false, true)) {
            var requests = 0
            var confirms = 0
            fixture { path ->
                when (path) {
                    VerificationHarness.requestPath -> {
                        requests++
                        if (confirmUnavailable) VerificationHarness.accepted
                        else VerificationHarness.accepted.copy(status = 404, body = null)
                    }
                    VerificationHarness.confirmPath -> {
                        confirms++
                        VerificationHarness.accepted.copy(status = 501, body = null)
                    }
                    else -> VerificationHarness.pending
                }
            }.use { (h, store) ->
                h.session.restore()
                val identity = h.session.localIdentity.value!!
                assertTrue(store.open(identity))
                store.requestCode()
                runCurrent()
                if (confirmUnavailable) {
                    store.changeCode("01234567")
                    store.confirmCode()
                    runCurrent()
                }
                assertEquals(AuthError.UnsupportedServer, store.state.value.error)
                assertNull(store.state.value.challenge)
                assertFalse(store.state.value.canRequest)
                store.close()
                assertTrue(store.open(identity))
                assertEquals(AuthError.UnsupportedServer, store.state.value.error)
                advanceTimeBy(60_000)
                runCurrent()
                store.requestCode()
                store.confirmCode()
                runCurrent()
                assertEquals(1, requests)
                assertEquals(if (confirmUnavailable) 1 else 0, confirms)
                assertEquals(identity, h.session.localIdentity.value)
                assertEquals(VerificationHarness.bearer, h.disk.session?.accessToken)
                assertFalse(h.session.currentPrivateAccess()!!.canRead)
            }
        }
    }

    @Test fun lateConfirmAfterLogoutCannotTriggerProfileOrReopenFlow() = runTest {
        val release = CompletableDeferred<Unit>()
        var profileCalls = 0
        var confirmCalls = 0
        fixture { path ->
            when (path) {
                VerificationHarness.requestPath -> VerificationHarness.accepted
                VerificationHarness.confirmPath -> {
                    confirmCalls++
                    release.await()
                    N2HttpFixtures.response(path, 204)
                }
                "/auth/logout" -> VerificationHarness.accepted.copy(status = 204, body = null)
                else -> {
                    profileCalls++
                    VerificationHarness.pending
                }
            }
        }.use { (h, store) ->
            h.session.restore()
            assertTrue(store.open(h.session.localIdentity.value!!))
            store.requestCode()
            runCurrent()
            store.changeCode("01234567")
            store.confirmCode()
            runCurrent()
            assertEquals(1, confirmCalls)
            h.session.logout()
            release.complete(Unit)
            runCurrent()
            assertEquals(1, profileCalls)
            assertNull(store.state.value.owner)
            assertNull(store.state.value.challenge)
            assertNull(h.disk.session)
        }
    }

    private fun TestScope.fixture(response: suspend (String) -> N2CapturedResponse): Fixture {
        val clock = { VerificationHarness.challengeExpiry - 120.seconds + testScheduler.currentTime.milliseconds }
        val harness = VerificationHarness(clock, StandardTestDispatcher(testScheduler)) {
            respondWith(response(it.url.encodedPath.removePrefix("/api/v1")))
        }
        return Fixture(harness, EmailVerificationStore(harness.client, harness.session, backgroundScope, clock))
    }

    private data class Fixture(val harness: VerificationHarness, val store: EmailVerificationStore) : AutoCloseable {
        override fun close() = harness.session.close()
    }
}
