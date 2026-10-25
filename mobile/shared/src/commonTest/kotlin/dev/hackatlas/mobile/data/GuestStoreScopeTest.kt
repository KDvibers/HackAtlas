package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.GuestStoreScope
import dev.hackatlas.mobile.domain.SavedEventKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GuestStoreScopeTest {
    @Test fun equivalentApiUrlsHaveOneIdentity() {
        val first = GuestStoreScope.Remote("https://API.example.test:443/api/v1")
        val second = GuestStoreScope.Remote("https://api.example.test/api/v1/")
        assertEquals(first, second)
        assertEquals("https://api.example.test/api/v1/", first.canonicalBaseUrl)
        assertEquals("remote-guest@https://api.example.test/api/v1/", first.documentScope)
        assertEquals(first.snapshotFileName(), second.snapshotFileName())
        assertEquals(GuestStoreScope.Remote("http://api.example.test:80/api/v1"),
            GuestStoreScope.Remote("http://api.example.test/api/v1/"))
    }

    @Test fun filenamesUseStableSha256AndNeverRawUrls() {
        val scope = GuestStoreScope.Remote("https://api.example.test/api/v1")
        assertEquals("remote-372212d728d63bceaa0d41ffe3264ebaa9141fdeb0b0b1fdfc6911584e824d20-v1.json", scope.snapshotFileName())
        assertTrue(scope.snapshotFileName().matches(Regex("remote-[0-9a-f]{64}-v1\\.json")))
        assertEquals("demo-v1.json", GuestStoreScope.BundledDemo.snapshotFileName())
        assertEquals("demo-guest", GuestStoreScope.BundledDemo.documentScope)
        assertFailsWith<IllegalArgumentException> { GuestStoreScope.Remote("https://user:secret@api.example.test/api/v1") }
    }

    @Test fun platformDigestMatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", storageNamespaceKey(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", storageNamespaceKey("abc"))
        assertEquals("573d06c03c0e2fb80b3346fd36d3b5e46a4fcbbc8696faa5e075d2124564c5a5",
            storageNamespaceKey("https://api.example.test/тест/api/v1/"))
    }

    @Test fun schemeHostPortAndBasePathRemainDistinct() {
        val scopes = origins()
        assertEquals(scopes.size, scopes.map { it.documentScope }.toSet().size)
        assertEquals(scopes.size, scopes.map { it.snapshotFileName() }.toSet().size)
        assertNotEquals(GuestStoreScope.Remote("https://api.example.test/Gateway/api/v1").snapshotFileName(),
            GuestStoreScope.Remote("https://api.example.test/gateway/api/v1").snapshotFileName())
    }

    @Test fun originsCannotReadOrOverwriteEachOther() = runTest {
        val scopes = origins()
        val detail = ContractJson.decodeFromString<HackathonDetailDto>(CanonicalFixtures.files.getValue("hackathon-detail.json")).toDomain()
        for (origin in scopes) {
            val disk = TestGuestStorage()
            val repository = GuestSavedRepository.open(disk, scope = origin)
            assertTrue(repository.entries.value.isEmpty())
            repository.save(detail)
            val before = disk.value
            val writes = disk.writes
            for (other in scopes.filter { it != origin }) {
                assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk, scope = other) }
            }
            assertFailsWith<GuestStorageException> { GuestSavedRepository.open(disk) }
            assertEquals(before, disk.value)
            assertEquals(writes, disk.writes)
            assertEquals(origin.documentScope, Json.parseToJsonElement(disk.value!!).jsonObject.getValue("scope").jsonPrimitive.content)
            assertEquals(listOf(SavedEventKey(detail.hackathon.id, true)),
                GuestSavedRepository.open(disk, scope = origin).entries.value.map { it.key })
        }
    }

    @Test fun repositoriesKeepTheirOriginalScopeWhenConfigurationChanges() = runTest {
        var configuredScope = GuestStoreScope.Remote("https://a.example.test/api/v1")
        val disks = mutableMapOf<String, TestGuestStorage>()
        fun storage(scope: GuestStoreScope) = disks.getOrPut(scope.snapshotFileName()) { TestGuestStorage() }
        val firstScope = configuredScope
        val first = GuestSavedRepository.open(storage(configuredScope), scope = configuredScope)
        configuredScope = GuestStoreScope.Remote("https://b.example.test/api/v1")
        val second = GuestSavedRepository.open(storage(configuredScope), scope = configuredScope)
        val detail = ContractJson.decodeFromString<HackathonDetailDto>(CanonicalFixtures.files.getValue("hackathon-detail.json")).toDomain()
        first.save(detail)
        assertEquals(firstScope, first.scope)
        assertEquals(configuredScope, second.scope)
        assertTrue(second.entries.value.isEmpty())
        assertTrue(GuestSavedRepository.open(storage(configuredScope), scope = configuredScope).entries.value.isEmpty())
        assertEquals(detail, GuestSavedRepository.open(storage(firstScope), scope = firstScope).entries.value.single().detail)
    }

    private fun origins() = listOf(
        "https://api.example.test/api/v1",
        "http://api.example.test/api/v1",
        "https://other.example.test/api/v1",
        "https://api.example.test:8443/api/v1",
        "https://api.example.test/gateway/api/v1",
    ).map { GuestStoreScope.Remote(it) }
}
