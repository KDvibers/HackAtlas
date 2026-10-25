package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.CatalogSource
import kotlin.test.*

class CatalogSourceTest {
    @Test fun normalizesEquivalentApiOrigins() {
        assertEquals("https://example.test/api/v1/", CatalogSource.Remote("https://EXAMPLE.test:443/api/v1").baseUrl)
        assertEquals("https://example.test/api/v1/", CatalogSource.Remote("https://example.test/api/v1/").baseUrl)
        assertEquals("https://example.test:8443/proxy/api/v1/", CatalogSource.Remote("https://example.test:8443/proxy/api/v1").baseUrl)
    }

    @Test fun rejectsAmbiguousOrCredentialBearingBaseUrls() {
        for (url in listOf("example.test/api/v1", "ftp://example.test/api/v1", "https://example.test",
            "https://user:secret@example.test/api/v1", "https://example.test/api/v1?key=secret",
            "https://example.test/api/v1#section", "https://example.test/api/v1/../api/v1",
            "https://example.test/ api/v1", "https:///api/v1")) {
            assertFailsWith<IllegalArgumentException>(url) { CatalogSource.Remote(url) }
        }
    }

    @Test fun httpRequiresExplicitDebugOptIn() {
        assertFailsWith<IllegalArgumentException> { CatalogSource.Remote("http://10.0.2.2:8080/api/v1") }
        assertEquals("http://10.0.2.2:8080/api/v1/",
            CatalogSource.Remote("http://10.0.2.2:8080/api/v1", allowDebugHttp = true).baseUrl)
    }
}
