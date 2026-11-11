package dev.hackatlas.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hackatlas.mobile.data.SavedGuestEvent
import dev.hackatlas.mobile.design.AtlasIcon
import dev.hackatlas.mobile.design.OutlineIcon
import dev.hackatlas.mobile.domain.*
import dev.hackatlas.mobile.resources.*
import kotlinx.datetime.*
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

private val CalendarPanel = RoundedCornerShape(20.dp)
private val DeadlineMarker = Color(0xFFC2AAFF)

@Composable
fun CalendarScreen(
    saved: List<SavedGuestEvent>,
    format: DisplayFormat,
    onOpen: (SavedGuestEvent) -> Unit,
    onCatalog: () -> Unit,
    statusContent: @Composable () -> Unit = {},
) {
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

    LazyColumn(
        Modifier.fillMaxSize().background(CatalogueColors.Background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HackAtlasBrand(showDemo = saved.any { it.detail.hackathon.isDemo })
            Spacer(Modifier.height(7.dp))
            Text(
                stringResource(Res.string.calendar), Modifier.semantics { heading() },
                color = CatalogueColors.Text,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 28.sp, lineHeight = 39.sp),
            )
            Text(
                stringResource(Res.string.calendar_subtitle),
                color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        item { statusContent() }
        if (saved.isEmpty()) {
            item { CalendarEmpty(onCatalog) }
        } else {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(CalendarPanel).background(CatalogueColors.Surface)
                        .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), CalendarPanel)
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    CalendarMonthHeader(months[month.monthNumber - 1] + " " + month.year, changeMonth) {
                        monthText = SavedCalendar.shiftMonth(today, 0).toString()
                        selectedText = today.toString()
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        week.forEach { label ->
                            Text(
                                label, Modifier.weight(1f), textAlign = TextAlign.Center,
                                color = CatalogueColors.Muted, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        grid.chunked(7).forEach { days ->
                            Row(Modifier.fillMaxWidth()) {
                                days.forEach { day ->
                                    val dayEntries = byDate.getValue(day)
                                    val events = dayEntries.count { it.kind == CalendarEntryKind.EVENT }
                                    val deadlines = dayEntries.count { it.kind == CalendarEntryKind.DEADLINE }
                                    val isSelected = day == selection
                                    val description = stringResource(
                                        Res.string.calendar_day_label, format.date(day.moment()), events, deadlines,
                                    )
                                    Column(
                                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                                selectedText = day.toString()
                                                monthText = SavedCalendar.shiftMonth(day, 0).toString()
                                            }
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = description
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Box(
                                            Modifier.sizeIn(minWidth = 34.dp, minHeight = 34.dp)
                                                .background(if (isSelected) CatalogueColors.Lime else Color.Transparent, CircleShape)
                                                .padding(horizontal = 3.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                day.dayOfMonth.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = when {
                                                    isSelected -> CatalogueColors.Ink
                                                    day == today -> CatalogueColors.Turquoise
                                                    day.monthNumber != month.monthNumber -> CatalogueColors.Muted.copy(alpha = .78f)
                                                    else -> CatalogueColors.Text
                                                },
                                                fontWeight = if (isSelected || day == today) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                            )
                                        }
                                        Row(
                                            Modifier.height(7.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (events > 0) CalendarMarker(CalendarEntryKind.EVENT)
                                            if (deadlines > 0) CalendarMarker(CalendarEntryKind.DEADLINE)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    CalendarLegend(stringResource(Res.string.calendar_events), CalendarEntryKind.EVENT, Modifier.weight(1f))
                    CalendarLegend(stringResource(Res.string.calendar_deadlines), CalendarEntryKind.DEADLINE, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.calendar_local_dates),
                    color = CatalogueColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            item { SectionTitle(format.date(selection.moment())) }
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.calendar_no_entries),
                        Modifier.fillMaxWidth().background(CatalogueColors.Surface, CalendarPanel).padding(16.dp),
                        color = CatalogueColors.Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(entries, key = { it.kind.name + ":" + it.saved.key.listKey() }) { entry ->
                CalendarEventCard(entry, format) { onOpen(entry.saved) }
            }
            if (undated.isNotEmpty()) {
                item { SectionTitle(stringResource(Res.string.calendar_undated)) }
                items(undated, key = { "undated:" + it.key.listKey() }) { snapshot ->
                    CalendarEventCard(SavedCalendarEntry(snapshot, CalendarEntryKind.EVENT, null), format) { onOpen(snapshot) }
                }
            }
        }
    }
}

private fun LocalDate.moment() = EventMoment(TimePrecision.DATE, toString(), null, null, null)

@Composable
private fun CalendarMonthHeader(title: String, onShift: (Int) -> Unit, onToday: () -> Unit) {
    val arrow = Modifier.clip(CircleShape).background(CatalogueColors.Background)
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            title, Modifier.weight(1f),
            color = CatalogueColors.Text, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 23.sp),
        )
        TextButton(onClick = onToday, contentPadding = PaddingValues(horizontal = 7.dp)) {
            Text(stringResource(Res.string.calendar_today), color = CatalogueColors.Turquoise, style = MaterialTheme.typography.labelMedium)
        }
        IconAction(
            AtlasIcon.ChevronRight, stringResource(Res.string.previous_month), { onShift(-1) },
            tint = CatalogueColors.Text, modifier = arrow.rotate(180f),
        )
        IconAction(
            AtlasIcon.ChevronRight, stringResource(Res.string.next_month), { onShift(1) },
            tint = CatalogueColors.Text, modifier = arrow,
        )
    }
}

@Composable
private fun CalendarMarker(kind: CalendarEntryKind) {
    val deadline = kind == CalendarEntryKind.DEADLINE
    Box(
        Modifier.size(5.dp).background(
            if (deadline) DeadlineMarker else CatalogueColors.Turquoise,
            if (deadline) RoundedCornerShape(1.dp) else CircleShape,
        ),
    )
}

@Composable
private fun CalendarLegend(label: String, kind: CalendarEntryKind, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        CalendarMarker(kind)
        Text(label, color = CatalogueColors.Muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CalendarEmpty(onCatalog: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(CatalogueColors.Surface, CalendarPanel)
            .border(1.dp, CatalogueColors.Border.copy(alpha = .65f), CalendarPanel).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlineIcon(AtlasIcon.Calendar, Modifier.size(28.dp), CatalogueColors.Turquoise)
        Text(
            stringResource(Res.string.calendar_empty), color = CatalogueColors.Text,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.calendar_empty_body), color = CatalogueColors.Muted,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        )
        Button(
            onClick = onCatalog, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CatalogueColors.Violet, contentColor = CatalogueColors.Text),
        ) {
            Text(stringResource(Res.string.go_catalog))
        }
    }
}

@Composable
private fun CalendarEventCard(entry: SavedCalendarEntry, format: DisplayFormat, onOpen: () -> Unit) {
    val event = entry.saved.detail.hackathon
    val deadline = entry.kind == CalendarEntryKind.DEADLINE
    val accent = if (deadline) CatalogueColors.Lime else CatalogueColors.Turquoise
    val surface = if (deadline) CatalogueColors.Violet else CatalogueColors.Surface
    val supportingText = if (deadline) CatalogueColors.Text else CatalogueColors.Muted
    val entryLabel = stringResource(if (deadline) Res.string.calendar_deadlines else Res.string.calendar_events)
    val demoLabel = if (event.isDemo) stringResource(Res.string.demo_label) else null
    val date = if (deadline) format.date(entry.moment) else format.range(event) + " · " + format.location(event)
    val time = format.exactTime(entry.moment)
    val timeLabel = when {
        time == null -> stringResource(Res.string.calendar_precision_note)
        deadline -> time
        else -> stringResource(Res.string.calendar_starts_at, time)
    }
    val eventStatus = when (event.eventStatus) {
        EventStatus.CANCELLED -> Res.string.event_cancelled
        EventStatus.POSTPONED -> Res.string.event_postponed
        EventStatus.ENDED -> Res.string.event_ended
        else -> null
    }

    Row(
        Modifier.fillMaxWidth().clip(CalendarPanel).background(surface)
            .clickable(role = Role.Button, onClick = onOpen).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
            OutlineIcon(if (deadline) AtlasIcon.Clock else AtlasIcon.Calendar, Modifier.size(22.dp), CatalogueColors.Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(entryLabel, demoLabel).joinToString(" · "),
                color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(event.title, color = CatalogueColors.Text, style = MaterialTheme.typography.titleMedium)
            Text(date, color = supportingText, style = MaterialTheme.typography.bodySmall)
            Text(timeLabel, color = supportingText, style = MaterialTheme.typography.bodySmall)
            eventStatus?.let {
                Text(stringResource(it), color = supportingText, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(Res.string.snapshot_captured, format.snapshotTimestamp(entry.saved.savedAt)),
                color = supportingText, style = MaterialTheme.typography.labelMedium,
            )
        }
        OutlineIcon(AtlasIcon.ChevronRight, Modifier.size(17.dp), CatalogueColors.Text)
    }
}
