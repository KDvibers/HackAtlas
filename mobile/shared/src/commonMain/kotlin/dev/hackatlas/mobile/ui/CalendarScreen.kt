package dev.hackatlas.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.design.*
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.resources.*
import kotlinx.datetime.*
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun CalendarScreen(saved: List<SavedGuestEvent>, format: DisplayFormat, onOpen: (SavedGuestEvent) -> Unit, onCatalog: () -> Unit) {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    var monthText by rememberSaveable { mutableStateOf(SavedCalendar.shiftMonth(today, 0).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = LocalDate.parse(monthText)
    val selection = LocalDate.parse(selectedText)
    val grid = remember(month) { SavedCalendar.monthGrid(month) }
    val byDate = remember(saved, month) { grid.associateWith { SavedCalendar.onDate(saved, it) } }
    val entries = byDate[selection].orEmpty()
    val undated = remember(saved) { SavedCalendar.undated(saved) }
    val months = stringArrayResource(Res.array.month_names)
    val week = stringArrayResource(Res.array.week_days)
    val changeMonth: (Int) -> Unit = { delta ->
        val next = SavedCalendar.shiftMonth(month, delta)
        monthText = next.toString()
        selectedText = next.toString()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelHeading(stringResource(Res.string.calendar), Modifier.weight(1f), fontSize = 25.sp)
                if (saved.any { it.detail.hackathon.isDemo }) {
                    Text(stringResource(Res.string.demo_label), color = AtlasColors.Primary, fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.calendar_subtitle), color = AtlasColors.TextSecondary, fontSize = 12.sp)
        }
        if (saved.isEmpty()) {
            item { StatePanel(stringResource(Res.string.calendar_empty), stringResource(Res.string.calendar_empty_body), stringResource(Res.string.go_catalog), onCatalog) }
        } else {
            item {
                val shape = RoundedCornerShape(14.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(AtlasColors.Surface).border(1.dp, AtlasColors.Border, shape).padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconAction(AtlasIcon.Back, stringResource(Res.string.previous_month), { changeMonth(-1) })
                        Text("${months[month.monthNumber - 1]} ${month.year}", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { monthText = SavedCalendar.shiftMonth(today, 0).toString(); selectedText = today.toString() }) {
                            Text(stringResource(Res.string.calendar_today), color = AtlasColors.Cyan, fontSize = 11.sp)
                        }
                        IconAction(AtlasIcon.ChevronRight, stringResource(Res.string.next_month), { changeMonth(1) })
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        week.forEach { Text(it, Modifier.weight(1f).wrapContentWidth(), color = AtlasColors.TextMuted, fontSize = 11.sp) }
                    }
                    grid.chunked(7).forEach { days ->
                        Row(Modifier.fillMaxWidth()) {
                            days.forEach { day ->
                                val dayEntries = byDate.getValue(day)
                                val events = dayEntries.count { it.kind == CalendarEntryKind.EVENT }
                                val deadlines = dayEntries.count { it.kind == CalendarEntryKind.DEADLINE }
                                val isSelected = day == selection
                                val description = stringResource(Res.string.calendar_day_label, format.date(day.moment()), events, deadlines)
                                Column(
                                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) AtlasColors.Primary else Color.Transparent)
                                        .clickable(role = Role.Button) { selectedText = day.toString(); monthText = SavedCalendar.shiftMonth(day, 0).toString() }
                                        .semantics(mergeDescendants = true) { contentDescription = description; selected = isSelected }
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(day.dayOfMonth.toString(), fontSize = 14.sp, color = when {
                                        isSelected -> AtlasColors.Background
                                        day.monthNumber != month.monthNumber -> AtlasColors.TextMuted
                                        day == today -> AtlasColors.Cyan
                                        else -> AtlasColors.TextPrimary
                                    }, fontWeight = if (isSelected || day == today) FontWeight.Bold else FontWeight.Normal)
                                    Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        if (events > 0) Box(Modifier.size(4.dp).background(if (isSelected) AtlasColors.Background else AtlasColors.Cyan, CircleShape))
                                        if (deadlines > 0) Box(Modifier.size(4.dp).background(if (isSelected) AtlasColors.Background else AtlasColors.Primary, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    CalendarLegend(stringResource(Res.string.calendar_events), AtlasColors.Cyan)
                    CalendarLegend(stringResource(Res.string.calendar_deadlines), AtlasColors.Primary)
                }
                Spacer(Modifier.height(9.dp))
                Text(stringResource(Res.string.calendar_local_dates), fontSize = 11.sp, lineHeight = 16.sp, color = AtlasColors.TextMuted)
            }
            item { SectionTitle(format.date(selection.moment())) }
            if (entries.isEmpty()) item { Text(stringResource(Res.string.calendar_no_entries), color = AtlasColors.TextSecondary, fontSize = 13.sp) }
            items(entries, key = { "${it.kind}:${it.saved.key.listKey()}" }) { entry ->
                CalendarEventCard(entry, format) { onOpen(entry.saved) }
            }
            if (undated.isNotEmpty()) {
                item { SectionTitle(stringResource(Res.string.calendar_undated)) }
                items(undated, key = { "undated:${it.key.listKey()}" }) { snapshot ->
                    CalendarEventCard(SavedCalendarEntry(snapshot, CalendarEntryKind.EVENT, null), format) { onOpen(snapshot) }
                }
            }
        }
    }
}

private fun LocalDate.moment() = EventMoment(TimePrecision.DATE, toString(), null, null, null)

@Composable
private fun CalendarLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(label, color = AtlasColors.TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun CalendarEventCard(entry: SavedCalendarEntry, format: DisplayFormat, onOpen: () -> Unit) {
    val event = entry.saved.detail.hackathon
    val deadline = entry.kind == CalendarEntryKind.DEADLINE
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(AtlasColors.Surface).border(1.dp, AtlasColors.Border, shape)
        .clickable(role = Role.Button, onClick = onOpen).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (deadline) Res.string.calendar_deadlines else Res.string.calendar_events), Modifier.weight(1f), color = if (deadline) AtlasColors.Primary else AtlasColors.Cyan, fontSize = 11.sp)
            if (event.isDemo) Text(stringResource(Res.string.demo_label), color = AtlasColors.Primary, fontSize = 9.sp, letterSpacing = 1.sp)
        }
        Text(event.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(if (deadline) format.date(entry.moment) else format.range(event), fontSize = 12.sp, color = AtlasColors.TextSecondary)
        val time = format.exactTime(entry.moment)
        Text(if (time == null) stringResource(Res.string.calendar_precision_note) else if (deadline) time else stringResource(Res.string.calendar_starts_at, time), fontSize = 11.sp, color = AtlasColors.TextMuted)
        if (event.eventStatus in setOf(EventStatus.CANCELLED, EventStatus.POSTPONED, EventStatus.ENDED)) {
            Text(stringResource(when (event.eventStatus) {
                EventStatus.CANCELLED -> Res.string.event_cancelled
                EventStatus.POSTPONED -> Res.string.event_postponed
                else -> Res.string.event_ended
            }), color = AtlasColors.TextSecondary, fontSize = 12.sp)
        }
        Text(stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(entry.saved.savedAt)), color = AtlasColors.TextMuted, fontSize = 10.sp)
    }
}
