package dev.hackatlas.mobile.data

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.threeten.bp.DateTimeException as BundledDateTimeException
import org.threeten.bp.Instant as BundledInstant
import org.threeten.bp.ZoneId as BundledZoneId
import org.threeten.bp.zone.ZoneRulesException as BundledZoneRulesException
import org.threeten.bp.zone.ZoneRulesProvider as BundledRules
import java.time.zone.ZoneRulesProvider as PlatformRules

// Android does not expose ZoneRulesProvider.getVersions. Its ICU and java.time
// providers share system tzdata. Local JVM tests use their own JDK provider.
private val platformTzdbVersion: String by lazy {
    if (System.getProperty("java.vm.name") == "Dalvik") {
        android.icu.util.TimeZone.getTZDataVersion()
    } else {
        PlatformRules.getVersions("UTC").lastKey()
    }
}

internal actual fun localEventTime(instant: Instant, zoneId: String): LocalDateTime {
    val platformVersion = try {
        TimeZone.of(zoneId)
        platformTzdbVersion
    } catch (_: IllegalArgumentException) { null }
    val bundledVersion = try {
        BundledRules.getVersions(zoneId).lastEntry()?.key
    } catch (_: BundledZoneRulesException) { null }

    if (!useBundledEventRules(platformVersion, bundledVersion)) {
        return instant.toLocalDateTime(TimeZone.of(zoneId))
    }
    return try {
        val local = BundledInstant.ofEpochSecond(instant.epochSeconds, instant.nanosecondsOfSecond.toLong())
            .atZone(BundledZoneId.of(zoneId)).toLocalDateTime()
        LocalDateTime(local.year, local.monthValue, local.dayOfMonth,
            local.hour, local.minute, local.second, local.nano)
    } catch (failed: BundledDateTimeException) {
        throw IllegalArgumentException("Invalid EventTime zone/instant", failed)
    }
}

private val versionPattern = Regex("[0-9]{4}[a-z]+")
private fun coreVersion(version: String): String? =
    version.removeSuffix("gtz").takeIf(versionPattern::matches)

/** Unknown version formats keep the OS provider; a zone missing in the OS can use bundled rules. */
internal fun useBundledEventRules(platformVersion: String?, bundledVersion: String?): Boolean {
    if (bundledVersion == null) return false
    if (platformVersion == null) return true
    val platform = coreVersion(platformVersion) ?: return false
    val bundled = coreVersion(bundledVersion) ?: return false
    return bundled > platform
}
