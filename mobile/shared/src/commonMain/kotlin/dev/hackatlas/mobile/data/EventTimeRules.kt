package dev.hackatlas.mobile.data

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime

/** Resolves API EventTime only; does not replace the device's global time rules. */
internal expect fun localEventTime(instant: Instant, zoneId: String): LocalDateTime
