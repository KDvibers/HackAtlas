package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.TimePrecision
import kotlin.test.*

class EventTimeValidationTest {
    private val item get() = ContractJson.decodeFromString<HackathonPageDto>(
        CanonicalFixtures.files.getValue("catalog-page.json")).items.first()

    private fun at(date: String, time: String, zone: String, instant: String) =
        item.copy(starts = EventTimeDto("MINUTE", date, time, zone, instant)).toDomain().starts!!

    @Test fun almatyUsesSixHoursBeforeTheChangeAndFiveHoursAfterIt() {
        val historical = at("2023-11-12", "10:00", "Asia/Almaty", "2023-11-12T04:00:00Z")
        val current = at("2026-11-12", "10:00", "Asia/Almaty", "2026-11-12T05:00:00Z")
        assertEquals("10:00", historical.time)
        assertEquals("10:00", current.time)
        assertEquals("2026-11-12T05:00:00Z", current.instant)
        assertEquals(TimePrecision.MINUTE, current.precision)
    }

    @Test fun nonexistentLocalTimeInDstGapIsStillRejected() {
        assertFailsWith<IllegalArgumentException> {
            at("2026-03-29", "02:30", "Europe/Paris", "2026-03-29T01:30:00Z")
        }
    }

    @Test fun bothValidInstantsInDstOverlapKeepTheSuppliedInstant() {
        for (instant in listOf("2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z")) {
            val mapped = at("2026-10-25", "02:30", "Europe/Paris", instant)
            assertEquals("02:30", mapped.time)
            assertEquals(instant, mapped.instant)
        }
    }

    @Test fun fractionalOffsetKeepsTheSourceDateAcrossUtcMidnight() {
        val mapped = at("2026-11-12", "00:15", "Asia/Kathmandu", "2026-11-11T18:30:00Z")
        assertEquals("2026-11-12", mapped.date)
        assertEquals("00:15", mapped.time)
        assertEquals("2026-11-11T18:30:00Z", mapped.instant)
    }

    @Test fun exactComparisonDoesNotDiscardSecondsOrNanoseconds() {
        for (instant in listOf("2026-11-12T05:00:01Z", "2026-11-12T05:00:00.000000001Z")) {
            assertFailsWith<IllegalArgumentException> { at("2026-11-12", "10:00", "Asia/Almaty", instant) }
        }
    }

    @Test fun invalidZoneAndInvalidInstantAreStillRejected() {
        assertFailsWith<IllegalArgumentException> { at("2026-11-12", "10:00", "Mars/Olympus", "2026-11-12T05:00:00Z") }
        assertFailsWith<IllegalArgumentException> { at("2026-11-12", "10:00", "Asia/Almaty", "invalid") }
    }
}
