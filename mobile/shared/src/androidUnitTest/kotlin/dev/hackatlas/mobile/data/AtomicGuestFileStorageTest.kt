package dev.hackatlas.mobile.data

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AtomicGuestFileStorageTest {
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
