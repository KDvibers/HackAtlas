package dev.hackatlas.mobile.ui

import dev.hackatlas.mobile.domain.*
import kotlinx.datetime.*

/** Presentation formatting never supplies a missing time or coerces unknown money to zero. */
class DisplayFormat(
    private val months: List<String>,
    private val unknownDate: String,
    private val unknownValue: String,
    private val unknownPrize: String,
    private val noPrize: String,
    private val nonCashPrize: String,
    private val online: String,
    private val offline: String,
    private val hybrid: String,
) {
    fun snapshotTimestamp(value: String): String = try {
        val local = Instant.parse(value).toLocalDateTime(TimeZone.currentSystemDefault())
        val moment = EventMoment(TimePrecision.DATE, local.date.toString(), null, null, null)
        "${date(moment)} · ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    } catch (_: IllegalArgumentException) { unknownDate }

    fun date(moment: EventMoment?, year: Boolean = true): String {
        val value = moment?.date ?: return unknownDate
        val parts = value.split('-')
        if (parts.size != 3) return unknownDate
        val month = parts[1].toIntOrNull()?.minus(1) ?: return unknownDate
        return "${parts[2].toIntOrNull() ?: parts[2]} ${months.getOrNull(month) ?: return unknownDate}" + if (year) " ${parts[0]}" else ""
    }

    fun range(event: Hackathon): String {
        val start = event.starts ?: return unknownDate
        val end = event.ends ?: return date(start)
        if (start.date == end.date) return date(start)
        if (start.date.take(7) == end.date.take(7)) {
            return "${start.date.takeLast(2).toIntOrNull()} – ${date(end)}"
        }
        return "${date(start, false)} – ${date(end)}"
    }

    fun exactTime(moment: EventMoment?): String? =
        if (moment?.precision == TimePrecision.MINUTE && moment.time != null)
            listOfNotNull(moment.time.take(5), moment.timeZone).joinToString(" · ") else null

    fun location(event: Hackathon): String = when (event.format) {
        EventFormat.ONLINE -> online
        EventFormat.OFFLINE -> event.location.city ?: offline
        EventFormat.HYBRID -> event.location.city?.let { "$hybrid · $it" } ?: hybrid
        EventFormat.UNSUPPORTED -> unknownValue
    }

    fun prize(prize: Prize): String {
        if (prize.status == PrizeStatus.NONE) return noPrize
        if (prize.status != PrizeStatus.ANNOUNCED) return unknownPrize
        val amount = prize.totalAmount ?: return if (prize.kind == PrizeKind.NON_CASH) nonCashPrize else unknownPrize
        val pieces = amount.split('.')
        val integer = pieces.first().reversed().chunked(3).joinToString(" ").reversed()
        val fraction = pieces.getOrNull(1)?.trimEnd('0').orEmpty()
        val number = integer + if (fraction.isNotEmpty()) ",$fraction" else ""
        return when (prize.currency) { "USD" -> "$ $number"; "EUR" -> "$number €"; "KZT" -> "$number ₸"; "RUB" -> "$number ₽"; else -> "$number ${prize.currency.orEmpty()}" }
    }

    fun team(team: TeamRules): String = when {
        team.minSize != null && team.maxSize != null -> if (team.minSize == team.maxSize) "${team.minSize}" else "${team.minSize} – ${team.maxSize}"
        else -> unknownValue
    }
}
