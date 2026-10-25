package dev.hackatlas.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Starter api/openapi.yaml v0.1.0. Strings preserve future enum values for explicit safe mapping. */
val ContractJson = Json { ignoreUnknownKeys = true }

@Serializable
data class EventTimeDto(
    val precision: String,
    val date: String,
    val time: String?,
    val timeZone: String?,
    val instant: String?,
)

@Serializable
data class OrganizerDto(val name: String, val websiteUrl: String?)

@Serializable
data class LocationDto(val countryCode: String?, val city: String?, val venue: String?)

@Serializable
data class TeamRulesDto(
    val minSize: Int?,
    val maxSize: Int?,
    val individualApplicationAllowed: Boolean?,
    val soloParticipationAllowed: Boolean?,
    val teamFormationAvailable: Boolean?,
)

@Serializable
data class PrizeDto(
    val status: String,
    val totalAmount: String?,
    val currency: String?,
    val kind: String,
    val summary: String?,
)

@Serializable
data class TagDto(val id: String, val label: String)

@Serializable
data class HackathonSummaryDto(
    val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val organizer: OrganizerDto,
    val format: String,
    val location: LocationDto,
    val starts: EventTimeDto?,
    val ends: EventTimeDto?,
    val registrationDeadline: EventTimeDto?,
    val registrationStatus: String,
    val eventStatus: String,
    val topics: List<TagDto>,
    val prize: PrizeDto,
    val team: TeamRulesDto,
    val imageUrl: String?,
    val artworkKey: String,
    val lastVerifiedAt: String?,
    val updatedAt: String,
    val isDemo: Boolean,
)

@Serializable
data class EligibilityDto(
    val summary: String?,
    val allowedCountryCodes: List<String>?,
    val minAge: Int?,
    val maxAge: Int?,
    val studentsOnly: Boolean?,
    val languages: List<String>,
)

@Serializable
data class StageDto(
    val title: String,
    @SerialName("when") val whenAt: EventTimeDto?,
    val description: String?,
)

@Serializable
data class HackathonDetailDto(
    val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val organizer: OrganizerDto,
    val format: String,
    val location: LocationDto,
    val starts: EventTimeDto?,
    val ends: EventTimeDto?,
    val registrationDeadline: EventTimeDto?,
    val registrationStatus: String,
    val eventStatus: String,
    val topics: List<TagDto>,
    val prize: PrizeDto,
    val team: TeamRulesDto,
    val imageUrl: String?,
    val artworkKey: String,
    val lastVerifiedAt: String?,
    val updatedAt: String,
    val isDemo: Boolean,
    val description: String,
    val eligibility: EligibilityDto,
    val stages: List<StageDto>,
    val prizeDetails: String?,
    val submissionRequirements: String?,
    val judgingCriteria: String?,
    val officialUrl: String,
    val registrationUrl: String?,
    val rulesUrl: String?,
    val sourceUrl: String,
) {
    fun summary() = HackathonSummaryDto(
        id, slug, title, shortDescription, organizer, format, location, starts, ends,
        registrationDeadline, registrationStatus, eventStatus, topics, prize, team,
        imageUrl, artworkKey, lastVerifiedAt, updatedAt, isDemo,
    )
}

@Serializable
data class HackathonPageDto(
    val items: List<HackathonSummaryDto>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val asOf: String,
)

@Serializable
data class CountryDto(val code: String, val label: String)

@Serializable
data class CityDto(val countryCode: String, val name: String)

@Serializable
data class FilterMetadataDto(
    val topics: List<TagDto>,
    val formats: List<TagDto>,
    val countries: List<CountryDto>,
    val cities: List<CityDto>,
    val interests: List<TagDto>,
)

@Serializable
data class ProfileStatsDto(val savedCount: Int, val appliedCount: Int, val interestsCount: Int)

@Serializable
data class ProfileDto(
    val id: String,
    val email: String,
    val displayName: String,
    val city: String?,
    val countryCode: String?,
    val roleLabel: String?,
    val avatarKey: String,
    val interests: List<String>,
    val stats: ProfileStatsDto,
)

@Serializable
data class FieldErrorDto(val field: String, val message: String)

@Serializable
data class ProblemDto(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val requestId: String,
    val fieldErrors: List<FieldErrorDto>,
)
