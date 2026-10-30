package dev.hackatlas.mobile.domain

import io.ktor.http.Url

/** Validated public website destination; API credentials are never part of this type. */
class SafeHttpsUrl private constructor(val value: String) {
    companion object {
        fun parse(raw: String?): SafeHttpsUrl? {
            if (raw == null || !raw.startsWith("https://", ignoreCase = true)) return null
            if (raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
            if (Regex("%(?![0-9a-f]{2})|%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return null
            val authority = raw.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
            if (authority.isEmpty() || '@' in authority || '%' in authority || authority.endsWith(':')) return null
            // Ktor normalizes an explicit port 0 to the protocol default.
            // Validate the original authority before allowing that normalization.
            val hostEnd = if (authority.startsWith('[')) authority.indexOf(']') + 1
                else authority.indexOf(':').takeIf { it >= 0 } ?: authority.length
            val port = authority.substring(hostEnd)
            if (port.isNotEmpty() && (port.first() != ':' || port.drop(1).any { it !in '0'..'9' } ||
                    port.drop(1).toIntOrNull() !in 1..65535)) return null
            val url = runCatching { Url(raw) }.getOrNull() ?: return null
            if (url.protocol.name != "https" || url.port !in 1..65535 || url.host.isBlank()) return null
            val host = url.host.removeSuffix(".")
            val validHost = if (':' in host) {
                isIpv6Literal(host)
            } else host.split('.').all {
                it.length in 1..63 && Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", RegexOption.IGNORE_CASE).matches(it)
            }
            return if (validHost) SafeHttpsUrl(url.toString()) else null
        }
    }
}

/** RFC 3986 section 3.2.2: eight 16-bit groups; one :: may omit at least one group. */
private fun isIpv6Literal(host: String): Boolean {
    if (!host.startsWith('[') || !host.endsWith(']')) return false
    val address = host.drop(1).dropLast(1)
    val compression = address.indexOf("::")
    val groups = if (compression < 0) address.split(':') else {
        if (address.indexOf("::", compression + 2) >= 0) return false
        listOf(address.take(compression), address.drop(compression + 2))
            .filter { it.isNotEmpty() }.flatMap { it.split(':') }
    }
    var groupCount = 0
    for ((index, group) in groups.withIndex()) {
        if ('.' in group) {
            // An IPv4 tail occupies the final two groups, never precedes ::.
            if (index != groups.lastIndex || !address.endsWith(group) || !isIpv4Tail(group)) return false
            groupCount += 2
        } else {
            if (group.length !in 1..4 || group.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
            groupCount++
        }
    }
    return if (compression < 0) groupCount == 8 else groupCount < 8
}

private fun isIpv4Tail(value: String): Boolean {
    val octets = value.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.length in 1..3 && octet.all { it in '0'..'9' } &&
            (octet.length == 1 || octet.first() != '0') && octet.toInt() <= 255
    }
}

enum class RegistrationActionState { AVAILABLE, DEMO_PREVIEW, CLOSED, CANCELLED, ENDED, UNAVAILABLE }
data class SharedEvent(val subject: String, val text: String)

data class EventLinkPolicy(
    val registrationState: RegistrationActionState,
    val registrationUrl: SafeHttpsUrl?,
    val officialUrl: SafeHttpsUrl?,
    val shareUrl: SafeHttpsUrl?,
    val isDemo: Boolean,
) {
    fun shareEvent(title: String, demoNotice: String): SharedEvent? = shareUrl?.let { url ->
        SharedEvent(title, listOfNotNull(demoNotice.takeIf { isDemo }, title, url.value).joinToString("\n"))
    }
}

/** Status is authoritative server/snapshot data; do not infer it from the device clock. */
fun eventLinkPolicy(detail: HackathonDetail, bundled: Boolean = false): EventLinkPolicy {
    val event = detail.hackathon
    val demo = bundled || event.isDemo
    val registration = SafeHttpsUrl.parse(detail.registrationUrl)
    val official = SafeHttpsUrl.parse(detail.officialUrl)
    val status = when {
        event.eventStatus == EventStatus.CANCELLED -> RegistrationActionState.CANCELLED
        event.eventStatus == EventStatus.ENDED -> RegistrationActionState.ENDED
        event.eventStatus == EventStatus.UNSUPPORTED -> RegistrationActionState.UNAVAILABLE
        event.registrationStatus == RegistrationStatus.CLOSED -> RegistrationActionState.CLOSED
        event.registrationStatus != RegistrationStatus.OPEN || registration == null -> RegistrationActionState.UNAVAILABLE
        demo -> RegistrationActionState.DEMO_PREVIEW
        else -> RegistrationActionState.AVAILABLE
    }
    return EventLinkPolicy(status, registration.takeIf { status == RegistrationActionState.AVAILABLE || status == RegistrationActionState.DEMO_PREVIEW },
        official, official ?: SafeHttpsUrl.parse(detail.sourceUrl), demo)
}
