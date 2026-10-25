package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.GuestStoreScope
import dev.hackatlas.mobile.domain.SavedEventKey
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AtomicGuestFileStorageTest {
    @Test fun namespacedFilesPreserveLegacyDataAndIsolateRemoteOrigins() = runTest {
        val directory = Files.createTempDirectory("hackatlas-namespaces-test").toFile()
        try {
            val bundled = AtomicGuestFileStorage(directory, "demo-v1.json")
            bundled.write(LEGACY_DEMO_GUEST_V1)
            val legacyRemote = directory.resolve("remote-v1.json").apply { writeText("legacy remote bytes") }
            val unknownRemote = directory.resolve("remote-unknown-v9.json").apply { writeText("unknown version bytes") }
            val firstScope = GuestStoreScope.Remote("https://first.example.test/api/v1")
            val secondScope = GuestStoreScope.Remote("https://second.example.test/api/v1")
            fun storage(scope: GuestStoreScope) = AtomicGuestFileStorage(directory, scope.snapshotFileName())
            val first = GuestSavedRepository.open(storage(firstScope), scope = firstScope)
            val second = GuestSavedRepository.open(storage(secondScope), scope = secondScope)
            assertTrue(first.entries.value.isEmpty())
            assertTrue(second.entries.value.isEmpty())
            val demo = GuestSavedRepository.open(bundled).entries.value.single().detail
            val real = demo.copy(hackathon = demo.hackathon.copy(isDemo = false))
            first.save(demo)
            first.save(real)
            second.save(real.copy(description = "Different source"))
            val secondBytes = storage(secondScope).read()
            GuestSavedRepository.open(storage(firstScope), scope = firstScope).remove(SavedEventKey(demo.hackathon.id, true))
            assertEquals(listOf(real), GuestSavedRepository.open(storage(firstScope), scope = firstScope).entries.value.map { it.detail })
            assertEquals(secondBytes, storage(secondScope).read())
            assertEquals("Different source", GuestSavedRepository.open(storage(secondScope), scope = secondScope).entries.value.single().detail.description)
            assertEquals(LEGACY_DEMO_GUEST_V1, bundled.read())
            assertEquals("legacy remote bytes", legacyRemote.readText())
            assertEquals("unknown version bytes", unknownRemote.readText())
            assertEquals(setOf("demo-v1.json", "remote-v1.json", "remote-unknown-v9.json",
                firstScope.snapshotFileName(), secondScope.snapshotFileName()), directory.list()!!.toSet())
        } finally {
            directory.listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(directory.toPath())
        }
    }

    @Test fun mismatchedScopeCannotReplaceAnExistingPhysicalFile() = runTest {
        val directory = Files.createTempDirectory("hackatlas-scope-test").toFile()
        try {
            val first = GuestStoreScope.Remote("https://first.example.test/api/v1")
            val second = GuestStoreScope.Remote("https://second.example.test/api/v1")
            val storage = AtomicGuestFileStorage(directory, first.snapshotFileName())
            GuestSavedRepository.open(storage, scope = first)
            val before = storage.read()
            assertFailsWith<GuestStorageException> { GuestSavedRepository.open(storage, scope = second) }
            assertEquals(before, storage.read())
            assertEquals(listOf(first.snapshotFileName()), directory.list()!!.toList())
        } finally {
            directory.listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(directory.toPath())
        }
    }

    @Test fun fileReplacementSurvivesNewAdapterAndKeepsUnicode() = runTest {
        val directory = Files.createTempDirectory("hackatlas-guest-test").toFile()
        try {
            val storage = AtomicGuestFileStorage(directory, "saved.json")
            assertNull(storage.read())
            storage.write("Первая копия")
            storage.write("Вторая копия · ₸")
            assertEquals("Вторая копия · ₸", AtomicGuestFileStorage(directory, "saved.json").read())
            assertEquals(listOf("saved.json"), directory.list()!!.toList())
        } finally {
            directory.listFiles()?.forEach { Files.delete(it.toPath()) }
            Files.delete(directory.toPath())
        }
    }

    @Test fun replacementFailureIsReportedInsteadOfClaimedSuccess() = runTest {
        val directory = Files.createTempDirectory("hackatlas-guest-test").toFile()
        val targetDirectory = directory.resolve("saved.json").apply { mkdir() }
        val original = targetDirectory.resolve("keep.txt").apply { writeText("preserved") }
        try {
            val storage = AtomicGuestFileStorage(directory, "saved.json")
            assertFailsWith<java.io.IOException> { storage.write("replacement") }
            assertEquals("preserved", original.readText())
            assertEquals(listOf("saved.json"), directory.list()!!.toList())
        } finally {
            Files.delete(original.toPath())
            Files.delete(targetDirectory.toPath())
            Files.delete(directory.toPath())
        }
    }
}
