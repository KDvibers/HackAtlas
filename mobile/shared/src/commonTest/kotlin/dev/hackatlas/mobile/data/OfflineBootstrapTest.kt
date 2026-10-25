package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.ui.AppState
import dev.hackatlas.mobile.ui.BootstrapState
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OfflineBootstrapTest {
    @Test fun savedDetailsRemainAvailableWhenCatalogCannotLoad() = runTest {
        val source = FixtureSource { CanonicalFixtures.files.getValue(it) }
        val disk = TestGuestStorage()
        val first = AppState(disk, source)
        first.load()
        val original = assertIs<BootstrapState.Ready>(first.state.value).saved.entries.value
        assertEquals(3, original.size)
        val restored = AppState(disk, FixtureSource { error("Catalog unavailable") })
        restored.load()
        val result = assertIs<BootstrapState.Ready>(restored.state.value)
        assertNull(result.repository)
        assertEquals(original, result.saved.entries.value)
    }

    @Test fun corruptedStorageIsExplicitFailureAndNeverGetsReset() = runTest {
        val disk = TestGuestStorage("broken")
        val state = AppState(disk, FixtureSource { CanonicalFixtures.files.getValue(it) })
        state.load()
        assertIs<BootstrapState.StorageFailed>(state.state.value)
        assertEquals("broken", disk.value)
        assertEquals(0, disk.writes)
    }

    @Test fun hostRecreationReusesReadyRepository() = runTest {
        val state = AppState(TestGuestStorage(), FixtureSource { CanonicalFixtures.files.getValue(it) })
        state.load()
        val first = assertIs<BootstrapState.Ready>(state.state.value)
        state.load()
        assertSame(first.saved, assertIs<BootstrapState.Ready>(state.state.value).saved)
    }
}
