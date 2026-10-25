package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.domain.*
import kotlin.test.*

class SourceNavigationTest {
    @Test fun releaseCannotSelectDemoOrHttpEvenWithDebugOptIn() {
        assertNull(selectCatalogSource(false, "DEMO", "https://api.example.test/api/v1/", true))
        assertNull(selectCatalogSource(false, "REMOTE", "http://api.example.test/api/v1/", true))
        assertNull(selectCatalogSource(false, "REMOTE", ""))
        assertEquals("https://api.example.test/api/v1/",
            assertIs<CatalogSource.Remote>(selectCatalogSource(false, "REMOTE", "https://API.example.test:443/api/v1")).baseUrl)
    }

    @Test fun debugRemoteIsExplicitAndNeverFallsBackToFixtures() {
        assertEquals(CatalogSource.BundledDemo, selectCatalogSource(true, "DEMO", ""))
        assertNull(selectCatalogSource(true, "REMOTE", ""))
        assertNull(selectCatalogSource(true, "UNKNOWN", "https://api.example.test/api/v1/"))
        assertNull(selectCatalogSource(true, "REMOTE", "http://10.0.2.2:8768/api/v1/"))
        assertIs<CatalogSource.Remote>(selectCatalogSource(true, "REMOTE", "http://10.0.2.2:8768/api/v1/", true))
    }

    @Test fun detailStateKeysSeparateOriginClassificationAndView() {
        val demo = GuestStoreScope.BundledDemo
        val one = GuestStoreScope.Remote("https://one.example.test/api/v1/")
        val two = GuestStoreScope.Remote("https://two.example.test/api/v1/")
        val keys = listOf(demo, one, two).flatMap { source ->
            listOf(true, false).flatMap { local ->
                listOf(true, false).flatMap { isDemo ->
                    listOf("a", "b").map { id -> source.detailStateKey(SavedEventKey(id, isDemo), local) }
                }
            }
        }
        assertEquals(24, keys.toSet().size)
        assertEquals(one.detailStateKey(SavedEventKey("a", false), true),
            GuestStoreScope.Remote("https://ONE.example.test:443/api/v1").detailStateKey(SavedEventKey("a", false), true))
    }

    @Test fun quickFiltersCombineAndCountryChangeDropsStaleCity() {
        val query = CatalogQuery(query = "AI", city = "Berlin", countryCode = "DE", sort = CatalogSort.NEWEST)
            .toggleQuickFilter(QuickFilter.ONLINE)
            .toggleQuickFilter(QuickFilter.KAZAKHSTAN)
            .toggleQuickFilter(QuickFilter.OPEN)
        assertEquals(EventFormat.ONLINE, query.format)
        assertEquals("KZ", query.countryCode)
        assertNull(query.city)
        assertEquals(RegistrationStatus.OPEN, query.registrationStatus)
        assertEquals("AI", query.query)
        assertEquals(CatalogSort.NEWEST, query.sort)
        assertNull(query.toggleQuickFilter(QuickFilter.ONLINE).format)
        val reset = query.toggleQuickFilter(QuickFilter.ALL)
        assertNull(reset.format)
        assertNull(reset.countryCode)
        assertNull(reset.registrationStatus)
        assertEquals("AI", reset.query)
    }
}
