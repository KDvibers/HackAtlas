package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private inline fun <reified T : Enum<T>> enumOrFallback(
    raw: String,
    fallback: T,
    field: String,
    warnings: MutableList<String>,
): T = enumValues<T>().firstOrNull { it.name == raw && it.name != "UNSUPPORTED" }
    ?: fallback.also { warnings += "$field=$raw" }

private fun EventTimeDto.toDomain(field: String, warnings: MutableList<String>): EventMoment {
    val precision = enumOrFallback(this.precision, TimePrecision.UNSUPPORTED, "$field.precision", warnings)
    LocalDate.parse(date)
    when (precision) {
        TimePrecision.DATE -> require(time == null && instant == null) { "$field DATE has invented time" }
        TimePrecision.MINUTE -> {
            require(time != null && timeZone != null && instant != null) { "$field MINUTE is incomplete" }
            val local = Instant.parse(instant).toLocalDateTime(TimeZone.of(timeZone))
            require(local.date == LocalDate.parse(date) && local.time == LocalTime.parse(time)) {
                "$field local time and instant disagree"
            }
        }
        TimePrecision.UNSUPPORTED -> Unit
    }
    // Future precision must not accidentally enable a precise countdown.
    return EventMoment(
        precision, date,
        time.takeUnless { precision == TimePrecision.UNSUPPORTED },
        timeZone,
        instant.takeUnless { precision == TimePrecision.UNSUPPORTED },
    )
}

fun HackathonSummaryDto.toDomain(): Hackathon {
    val warnings = mutableListOf<String>()
    val format = enumOrFallback(format, EventFormat.UNSUPPORTED, "format", warnings)
    val registration = enumOrFallback(registrationStatus, RegistrationStatus.UNSUPPORTED, "registrationStatus", warnings)
    val event = enumOrFallback(eventStatus, EventStatus.UNSUPPORTED, "eventStatus", warnings)
    val prizeStatus = enumOrFallback(prize.status, PrizeStatus.UNSUPPORTED, "prize.status", warnings)
    val prizeKind = enumOrFallback(prize.kind, PrizeKind.UNSUPPORTED, "prize.kind", warnings)
    val artwork = enumOrFallback(artworkKey, Artwork.WIREFRAME, "artworkKey", warnings)
    val starts = starts?.toDomain("starts", warnings)
    val ends = ends?.toDomain("ends", warnings)
    val deadline = registrationDeadline?.toDomain("registrationDeadline", warnings)
    return Hackathon(
        id = id, slug = slug, title = title, shortDescription = shortDescription,
        organizerName = organizer.name, organizerWebsiteUrl = organizer.websiteUrl,
        format = format, location = Location(location.countryCode, location.city, location.venue),
        starts = starts, ends = ends, registrationDeadline = deadline,
        registrationStatus = registration, eventStatus = event,
        topics = topics.map { Tag(it.id, it.label) },
        prize = Prize(prizeStatus, prize.totalAmount, prize.currency, prizeKind, prize.summary),
        team = TeamRules(team.minSize, team.maxSize, team.individualApplicationAllowed,
            team.soloParticipationAllowed, team.teamFormationAvailable),
        imageUrl = imageUrl, artwork = artwork,
        lastVerifiedAt = lastVerifiedAt, updatedAt = updatedAt, isDemo = isDemo,
        contractWarnings = warnings.toList(),
    )
}

fun HackathonDetailDto.toDomain(): HackathonDetail {
    val summary = summary().toDomain()
    val warnings = summary.contractWarnings.toMutableList()
    val mappedStages = stages.mapIndexed { index, stage ->
        Stage(stage.title, stage.whenAt?.toDomain("stages[$index].when", warnings), stage.description)
    }
    return HackathonDetail(
        hackathon = summary.copy(contractWarnings = warnings.toList()),
        description = description,
        eligibility = Eligibility(eligibility.summary, eligibility.allowedCountryCodes,
            eligibility.minAge, eligibility.maxAge, eligibility.studentsOnly, eligibility.languages),
        stages = mappedStages, prizeDetails = prizeDetails,
        submissionRequirements = submissionRequirements, judgingCriteria = judgingCriteria,
        officialUrl = officialUrl, registrationUrl = registrationUrl, rulesUrl = rulesUrl, sourceUrl = sourceUrl,
    )
}

fun FilterMetadataDto.toDomain() = FilterMetadata(
    topics = topics.map { Tag(it.id, it.label) },
    formats = formats.map { Tag(it.id, it.label) },
    countries = countries.map { Country(it.code, it.label) },
    cities = cities.map { City(it.countryCode, it.name) },
    interests = interests.map { Tag(it.id, it.label) },
)

fun ProfileDto.toDemoDomain(metadata: FilterMetadata): DemoProfile {
    val warnings = mutableListOf<String>()
    val avatar = enumOrFallback(avatarKey, Avatar.INITIALS, "avatarKey", warnings)
    val labels = metadata.interests.associateBy { it.id }
    val interests = interests.map { id ->
        labels[id] ?: Tag(id, id).also { warnings += "interests=$id" }
    }
    return DemoProfile(
        id, email, displayName, city, countryCode, roleLabel, avatar, interests,
        stats.savedCount, stats.appliedCount, interests.size,
        contractWarnings = warnings.toList(),
    )
}
