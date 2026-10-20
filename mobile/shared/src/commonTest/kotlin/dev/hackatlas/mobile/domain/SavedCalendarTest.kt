package dev.hackatlas.mobile.domain

import dev.hackatlas.mobile.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

class SavedCalendarTest {
    private suspend fun saved(): SavedGuestEvent {
        val demo = DemoRepository.load(FixtureSource { CanonicalFixtures.files.getValue(it) })
        return SavedGuestEvent(demo.detail("39287368-5259-5235-a1c4-25b4e1e8421e")!!, "2026-10-20T12:00:00Z")
    }

    @Test fun eventCoversInclusiveDateRange() = runTest {
        val entries = listOf(saved())
        for (day in 12..14) assertEquals(CalendarEntryKind.EVENT, SavedCalendar.onDate(entries, LocalDate(2026, 11, day)).single().kind)
        assertTrue(SavedCalendar.onDate(entries, LocalDate(2026, 11, 15)).isEmpty())
    }

    @Test fun deadlineIsIndependentFromEventDateAndKeepsPrecision() = runTest {
        val item = saved()
        val entries = SavedCalendar.onDate(listOf(item), LocalDate(2026, 11, 1))
        assertEquals(CalendarEntryKind.DEADLINE, entries.single().kind)
        assertEquals(item.detail.hackathon.registrationDeadline, entries.single().moment)
    }

    @Test fun dateOnlyDeadlineNeverGetsInventedTime() = runTest {
        val item = saved()
        val dateOnly = EventMoment(TimePrecision.DATE, "2026-11-12", null, null, null)
        val changed = item.copy(detail = item.detail.copy(hackathon = item.detail.hackathon.copy(registrationDeadline = dateOnly)))
        val entries = SavedCalendar.onDate(listOf(changed), LocalDate(2026, 11, 12))
        assertEquals(setOf(CalendarEntryKind.EVENT, CalendarEntryKind.DEADLINE), entries.map { it.kind }.toSet())
        assertNull(entries.first { it.kind == CalendarEntryKind.DEADLINE }.moment?.time)
    }

    @Test fun missingDatesStayInUndatedList() = runTest {
        val item = saved()
        val changed = item.copy(detail = item.detail.copy(hackathon = item.detail.hackathon.copy(starts = null, ends = null, registrationDeadline = null)))
        assertEquals(listOf(changed), SavedCalendar.undated(listOf(changed)))
        assertTrue(SavedCalendar.onDate(listOf(changed), LocalDate(2026, 11, 12)).isEmpty())
    }

    @Test fun cancelledSavedEventIsKeptWithItsStatus() = runTest {
        val item = saved()
        val changed = item.copy(detail = item.detail.copy(hackathon = item.detail.hackathon.copy(eventStatus = EventStatus.CANCELLED)))
        assertEquals(EventStatus.CANCELLED, SavedCalendar.onDate(listOf(changed), LocalDate(2026, 11, 12)).single().saved.detail.hackathon.eventStatus)
    }

    @Test fun malformedOrReverseRangeDoesNotCreatePhantomDays() = runTest {
        val item = saved()
        val event = item.detail.hackathon
        val changed = item.copy(detail = item.detail.copy(hackathon = event.copy(ends = event.ends!!.copy(date = "2026-11-01"))))
        assertEquals(1, SavedCalendar.onDate(listOf(changed), LocalDate(2026, 11, 12)).size)
        assertTrue(SavedCalendar.onDate(listOf(changed), LocalDate(2026, 11, 13)).isEmpty())
        val unknown = changed.copy(detail = changed.detail.copy(hackathon = event.copy(starts = event.starts!!.copy(date = "bad"), registrationDeadline = null)))
        assertEquals(1, SavedCalendar.undated(listOf(unknown)).size)
    }

    @Test fun monthGridStartsMondayAndIncludesLeapDay() {
        val grid = SavedCalendar.monthGrid(LocalDate(2028, 2, 1))
        assertEquals(0, grid.first().dayOfWeek.ordinal)
        assertTrue(LocalDate(2028, 2, 29) in grid)
        assertEquals(0, grid.size % 7)
        assertEquals(grid.size, grid.toSet().size)
    }

    @Test fun monthNavigationCrossesYearBoundary() {
        assertEquals(LocalDate(2027, 1, 1), SavedCalendar.shiftMonth(LocalDate(2026, 12, 19), 1))
        assertEquals(LocalDate(2026, 12, 1), SavedCalendar.shiftMonth(LocalDate(2027, 1, 1), -1))
    }
}
