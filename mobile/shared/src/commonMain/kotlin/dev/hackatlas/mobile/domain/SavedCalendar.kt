package dev.hackatlas.mobile.domain

import dev.hackatlas.mobile.data.SavedGuestEvent
import kotlinx.datetime.*

enum class CalendarEntryKind { EVENT, DEADLINE }
data class SavedCalendarEntry(val saved: SavedGuestEvent, val kind: CalendarEntryKind, val moment: EventMoment?)

/** Calendar dates follow the source's local dates, not device-zone conversions or invented midnight instants. */
object SavedCalendar {
    fun onDate(saved: List<SavedGuestEvent>, date: LocalDate): List<SavedCalendarEntry> =
        saved.flatMap { item ->
            val event = item.detail.hackathon
            val start = parse(event.starts)
            val end = parse(event.ends)?.takeIf { start != null && it >= start } ?: start
            buildList {
                if (parse(event.registrationDeadline) == date)
                    add(SavedCalendarEntry(item, CalendarEntryKind.DEADLINE, event.registrationDeadline))
                if (start != null && end != null && date in start..end)
                    add(SavedCalendarEntry(item, CalendarEntryKind.EVENT, event.starts))
            }
        }.sortedWith(compareBy({ it.kind != CalendarEntryKind.DEADLINE }, { it.saved.detail.hackathon.title }, { it.saved.detail.hackathon.id }))

    fun undated(saved: List<SavedGuestEvent>): List<SavedGuestEvent> = saved.filter {
        parse(it.detail.hackathon.starts) == null && parse(it.detail.hackathon.registrationDeadline) == null
    }

    fun shiftMonth(date: LocalDate, delta: Int): LocalDate =
        LocalDate(date.year, date.monthNumber, 1).plus(delta, DateTimeUnit.MONTH)

    fun monthGrid(month: LocalDate): List<LocalDate> {
        val first = shiftMonth(month, 0)
        val next = shiftMonth(first, 1)
        val start = first.minus(first.dayOfWeek.ordinal, DateTimeUnit.DAY)
        val count = ((start.daysUntil(next) + 6) / 7) * 7
        return List(count) { start.plus(it, DateTimeUnit.DAY) }
    }

    private fun parse(moment: EventMoment?): LocalDate? =
        if (moment == null || moment.precision == TimePrecision.UNSUPPORTED) null
        else try { LocalDate.parse(moment.date) } catch (_: IllegalArgumentException) { null }
}
