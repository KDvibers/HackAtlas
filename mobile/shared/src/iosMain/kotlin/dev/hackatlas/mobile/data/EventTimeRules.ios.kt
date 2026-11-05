package dev.hackatlas.mobile.data

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal actual fun localEventTime(instant: Instant, zoneId: String): LocalDateTime =
    instant.toLocalDateTime(TimeZone.of(zoneId))
