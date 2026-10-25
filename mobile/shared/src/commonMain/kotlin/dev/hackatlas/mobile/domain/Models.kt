package dev.hackatlas.mobile.domain

import kotlinx.serialization.Serializable

/** UI/domain values are independent of transport DTOs and never carry a guessed date or amount. */
enum class EventFormat { ONLINE, OFFLINE, HYBRID, UNSUPPORTED }
enum class RegistrationStatus { OPEN, CLOSED, UPCOMING, UNKNOWN, UNSUPPORTED }
enum class EventStatus { SCHEDULED, ONGOING, ENDED, POSTPONED, CANCELLED, UNSUPPORTED }
enum class TimePrecision { DATE, MINUTE, UNSUPPORTED }
enum class PrizeStatus { ANNOUNCED, NONE, UNKNOWN, UNSUPPORTED }
enum class PrizeKind { CASH, NON_CASH, MIXED, UNSPECIFIED, UNSUPPORTED }
enum class Artwork { AI_BUST, FINTECH_CITY, GAMEPAD, GREENTECH, WIREFRAME }
enum class Avatar { HOODED, INITIALS }
enum class CatalogSort { DEADLINE_ASC, START_ASC, NEWEST }

@Serializable
data class EventMoment(
    val precision: TimePrecision,
    val date: String,
    val time: String?,
    val timeZone: String?,
    val instant: String?,
)

@Serializable data class Tag(val id: String, val label: String)
@Serializable data class Location(val countryCode: String?, val city: String?, val venue: String?)
@Serializable data class Prize(
    val status: PrizeStatus,
    val totalAmount: String?,
    val currency: String?,
    val kind: PrizeKind,
    val summary: String?,
)
@Serializable data class TeamRules(
    val minSize: Int?,
    val maxSize: Int?,
    val individualApplicationAllowed: Boolean?,
    val soloParticipationAllowed: Boolean?,
    val teamFormationAvailable: Boolean?,
)

@Serializable data class Hackathon(
    val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val organizerName: String,
    val organizerWebsiteUrl: String?,
    val format: EventFormat,
    val location: Location,
    val starts: EventMoment?,
    val ends: EventMoment?,
    val registrationDeadline: EventMoment?,
    val registrationStatus: RegistrationStatus,
    val eventStatus: EventStatus,
    val topics: List<Tag>,
    val prize: Prize,
    val team: TeamRules,
    val imageUrl: String?,
    val artwork: Artwork,
    val lastVerifiedAt: String?,
    val updatedAt: String,
    val isDemo: Boolean,
    /** Unknown contract values are retained for diagnostics instead of silently masquerading as valid. */
    val contractWarnings: List<String> = emptyList(),
)

@Serializable data class Eligibility(
    val summary: String?,
    val allowedCountryCodes: List<String>?,
    val minAge: Int?,
    val maxAge: Int?,
    val studentsOnly: Boolean?,
    val languages: List<String>,
)
@Serializable data class Stage(val title: String, val whenAt: EventMoment?, val description: String?)
@Serializable data class HackathonDetail(
    val hackathon: Hackathon,
    val description: String,
    val eligibility: Eligibility,
    val stages: List<Stage>,
    val prizeDetails: String?,
    val submissionRequirements: String?,
    val judgingCriteria: String?,
    val officialUrl: String,
    val registrationUrl: String?,
    val rulesUrl: String?,
    val sourceUrl: String,
)

data class Country(val code: String, val label: String)
data class City(val countryCode: String, val name: String)
data class FilterMetadata(
    val topics: List<Tag>,
    val formats: List<Tag>,
    val countries: List<Country>,
    val cities: List<City>,
    val interests: List<Tag>,
)

data class DemoProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val city: String?,
    val countryCode: String?,
    val roleLabel: String?,
    val avatar: Avatar,
    val interests: List<Tag>,
    val savedCount: Int,
    val appliedCount: Int,
    val interestsCount: Int,
    val isDemo: Boolean = true,
    val contractWarnings: List<String> = emptyList(),
)

data class CatalogQuery(
    val query: String = "",
    val format: EventFormat? = null,
    val countryCode: String? = null,
    val city: String? = null,
    val topic: String? = null,
    val registrationStatus: RegistrationStatus? = null,
    val individualApplicationAllowed: Boolean? = null,
    val startsFrom: String? = null,
    val startsTo: String? = null,
    val sort: CatalogSort = CatalogSort.DEADLINE_ASC,
    val page: Int = 0,
    val size: Int = 20,
)
data class CatalogPage(
    val items: List<Hackathon>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val asOf: String,
)
