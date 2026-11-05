package dev.hackatlas.mobile.data

import java.io.DataInputStream
import kotlin.test.*

class EventTimeRulesTest {
    @Test fun platformVersionPolicyKeepsNewerOsRulesAndUpgradesOldRules() {
        assertTrue(useBundledEventRules("2023a", "2026egtz"))
        assertFalse(useBundledEventRules("2026f", "2026egtz"))
        assertFalse(useBundledEventRules("2026e", "2026egtz"))
        assertTrue(useBundledEventRules(null, "2026egtz"))
        assertFalse(useBundledEventRules("2025a", null))
        assertFalse(useBundledEventRules(null, null))
        assertFalse(useBundledEventRules("vendor-rules", "2026egtz"))
        assertFalse(useBundledEventRules("2025a", "vendor-rules"))
    }

    @Test fun androidPackageContainsRulesIndependentOfTheOs() {
        val resource = javaClass.getResourceAsStream("/org/threeten/bp/TZDB.dat")
        assertNotNull(resource, "Android needs bundled timezone rules when OS tzdata is old")
        DataInputStream(resource).use { input ->
            assertEquals(1, input.readUnsignedByte())
            assertEquals("TZDB", input.readUTF())
            val versions = List(input.readUnsignedShort()) { input.readUTF() }
            assertTrue(versions.any { it.removeSuffix("gtz") >= "2024a" }, "Almaty UTC+5 rules are required")
        }
    }
}
