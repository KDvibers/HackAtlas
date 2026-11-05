package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.data.auth.ApplicationMark
import dev.hackatlas.mobile.ui.applications.*
import kotlin.test.*

class ApplicationVisibilityTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val privateState = ApplicationUiState(mode = ApplicationMode.ACCOUNT, ownerId = owner,
        marks = listOf(ApplicationMark("39287368-5259-5235-a1c4-25b4e1e8421e","APPLIED",true,"2026-11-05T10:00:00Z")), known = true)

    @Test fun identityChangeHidesPrivateMarksBeforeTheAsyncStoreCollectorRuns() {
        for(identity in listOf(null,"22222222-2222-4222-8222-222222222222")) {
            val visible = privateState.visibleFor(identity,false)
            assertEquals(ApplicationMode.CHECKING,visible.mode)
            assertTrue(visible.marks.isEmpty());assertFalse(visible.known);assertNull(visible.ownerId)
        }
    }
    @Test fun logoutHidesMarksEvenWhileOldIdentityIsStillPresent() {
        assertTrue(privateState.visibleFor(owner,true).marks.isEmpty())
    }
    @Test fun sameOwnerCanViewCacheWithoutClaimingWritePermission() {
        val cached = privateState.copy(fromCache = true,writable = false)
        assertEquals(cached,cached.visibleFor(owner,false))
    }
}
