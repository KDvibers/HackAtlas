package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.AccountAccessMode
import dev.hackatlas.mobile.domain.CatalogSource
import dev.hackatlas.mobile.domain.selectCatalogRuntimeConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CatalogRuntimeConfigurationTest {
    @Test fun ordinaryDebugKeepsLegacyForDemoAndRemote() {
        val demo = selectCatalogRuntimeConfiguration(true, "DEMO", "")
        assertEquals(AccountAccessMode.LEGACY, demo.accessMode)
        assertEquals(CatalogSource.BundledDemo, demo.source)

        val remote = selectCatalogRuntimeConfiguration(true, "REMOTE", httpsUrl)
        assertEquals(AccountAccessMode.LEGACY, remote.accessMode)
        assertIs<CatalogSource.Remote>(remote.source)
    }

    @Test fun releaseIgnoresAccountModeAndDebugHttpOptIns() {
        val remote = selectCatalogRuntimeConfiguration(false, "REMOTE", httpsUrl, "N2_REQUIRED", true)
        assertEquals(AccountAccessMode.LEGACY, remote.accessMode)
        assertIs<CatalogSource.Remote>(remote.source)
        assertNull(selectCatalogRuntimeConfiguration(false, "REMOTE", httpUrl, "N2_REQUIRED", true).source)
        assertNull(selectCatalogRuntimeConfiguration(false, "DEMO", "", "N2_REQUIRED").source)
    }

    @Test fun closedDebugRequiresAnExplicitValidRemoteConfiguration() {
        val remote = selectCatalogRuntimeConfiguration(true, "REMOTE", httpsUrl, "N2_REQUIRED")
        assertEquals(AccountAccessMode.N2_REQUIRED, remote.accessMode)
        assertEquals(httpsUrl, assertIs<CatalogSource.Remote>(remote.source).baseUrl)

        val malformed = selectCatalogRuntimeConfiguration(true, "REMOTE", "https://example.test", "N2_REQUIRED")
        assertNull(malformed.source)
        assertEquals(AccountAccessMode.LEGACY, malformed.accessMode)
        assertNull(selectCatalogRuntimeConfiguration(true, "DEMO", httpsUrl, "N2_REQUIRED").source)
        assertNull(selectCatalogRuntimeConfiguration(true, "REMOTE", httpsUrl, "").source)
        assertNull(selectCatalogRuntimeConfiguration(true, "REMOTE", httpsUrl, "UNKNOWN").source)
    }

    @Test fun closedDebugHttpRequiresHostOptIn() {
        assertNull(selectCatalogRuntimeConfiguration(true, "REMOTE", httpUrl, "N2_REQUIRED").source)
        val configuration = selectCatalogRuntimeConfiguration(true, "REMOTE", httpUrl, "N2_REQUIRED", true)
        assertEquals(AccountAccessMode.N2_REQUIRED, configuration.accessMode)
        assertEquals(httpUrl, assertIs<CatalogSource.Remote>(configuration.source).baseUrl)
    }

    private val httpsUrl = "https://example.test/api/v1/"
    private val httpUrl = "http://10.0.2.2:8081/api/v1/"
}
