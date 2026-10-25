package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.decodeFromString

fun interface FixtureSource {
    suspend fun readText(name: String): String
}

class InvalidCatalogQueryException(val field: String, message: String) : IllegalArgumentException(message)

/** Explicit demo adapter. Saves live only for this repository instance, never a real account/session. */
class DemoRepository private constructor(
    private val allHackathons: List<Hackathon>,
    private val details: Map<String, HackathonDetail>,
    override val metadata: FilterMetadata,
    override val asOf: String,
    initialProfile: DemoProfile,
    initialSaved: Set<String>,
) : HackathonRepository, ProfileRepository, SavedRepository {
    private val byId = allHackathons.associateBy { it.id }
    private val newestRank = allHackathons.mapIndexed { index, item -> item.id to index }.toMap()
    private val mutableSaved = MutableStateFlow(initialSaved.toSet())
    override val savedIds: StateFlow<Set<String>> = mutableSaved.asStateFlow()
    private val mutableProfile = MutableStateFlow(initialProfile.copy(savedCount = initialSaved.size))
    override val profile: StateFlow<DemoProfile> = mutableProfile.asStateFlow()

    override fun catalog(query: CatalogQuery): CatalogPage {
        validate(query)
        val search = query.query.trim()
        val filtered = allHackathons.filter { item ->
            item.eventStatus != EventStatus.ENDED && item.eventStatus != EventStatus.CANCELLED &&
                (search.isEmpty() || sequenceOf(item.title, item.organizerName, item.location.city.orEmpty())
                    .plus(item.topics.asSequence().map { it.label }).any { it.contains(search, ignoreCase = true) }) &&
                (query.format == null || item.format == query.format) &&
                (query.countryCode == null || item.location.countryCode == query.countryCode) &&
                (query.city == null || item.location.city.equals(query.city, ignoreCase = true)) &&
                (query.topic == null || item.topics.any { it.id == query.topic }) &&
                (query.registrationStatus == null || item.registrationStatus == query.registrationStatus) &&
                (query.individualApplicationAllowed == null || item.team.individualApplicationAllowed == query.individualApplicationAllowed) &&
                (query.startsFrom == null || (item.starts?.date?.compareTo(query.startsFrom) ?: -1) >= 0) &&
                (query.startsTo == null || (item.starts?.date?.compareTo(query.startsTo) ?: 1) <= 0)
        }.sortedWith(comparator(query.sort))
        val total = filtered.size
        val pages = if (total == 0) 0 else (total - 1) / query.size + 1
        // Long avoids overflow for a syntactically valid but very large page parameter.
        val start = query.page.toLong() * query.size
        val items = if (start >= total) emptyList() else filtered.drop(start.toInt()).take(query.size)
        return CatalogPage(items, query.page, query.size, total, pages, query.page < pages - 1, asOf)
    }

    override fun detail(id: String): HackathonDetail? = details[id]

    override fun savedHackathons(): List<Hackathon> = savedIds.value.mapNotNull(byId::get)

    override fun toggleSaved(id: String) = setSaved(id, id !in savedIds.value)

    override fun setSaved(id: String, saved: Boolean) {
        require(id in byId) { "Cannot save an unknown demo event: $id" }
        if ((id in mutableSaved.value) == saved) return
        // Linked insertion order models newest save first, without inventing server savedAt timestamps.
        mutableSaved.value = if (saved) setOf(id) + mutableSaved.value else mutableSaved.value - id
        mutableProfile.value = mutableProfile.value.copy(savedCount = mutableSaved.value.size)
    }

    private fun comparator(sort: CatalogSort): Comparator<Hackathon> = Comparator { first, second ->
        val primary = when (sort) {
            CatalogSort.NEWEST -> newestRank.getValue(first.id).compareTo(newestRank.getValue(second.id))
            CatalogSort.DEADLINE_ASC -> compareMoment(first.registrationDeadline, second.registrationDeadline)
            CatalogSort.START_ASC -> compareMoment(first.starts, second.starts)
        }
        if (primary == 0) first.id.compareTo(second.id) else primary
    }

    companion object {
        const val FIXTURE_AS_OF = "2026-10-12T10:00:00Z"
        private val INITIAL_SAVED_IDS = linkedSetOf(
            "39287368-5259-5235-a1c4-25b4e1e8421e", // AI
            "9756e7f3-5796-5d69-93cf-a6a6cb8ebd33", // GameDev
            "fc6f0e31-af57-52ac-a736-3d0cce49b3fa", // GreenTech
        )

        suspend fun load(source: FixtureSource): DemoRepository {
            val page = ContractJson.decodeFromString<HackathonPageDto>(source.readText("catalog-page.json"))
            val detailDto = ContractJson.decodeFromString<HackathonDetailDto>(source.readText("hackathon-detail.json"))
            val metadata = ContractJson.decodeFromString<FilterMetadataDto>(source.readText("filter-metadata.json")).toDomain()
            val profile = ContractJson.decodeFromString<ProfileDto>(source.readText("profile.json")).toDemoDomain(metadata)
            require(page.asOf == FIXTURE_AS_OF) { "Demo clock changed: review fixtures together" }
            require(page.items.all { it.isDemo } && detailDto.isDemo) { "DemoRepository only accepts explicit demo data" }
            require(page.items.map { it.id }.distinct().size == page.items.size) { "Duplicate demo event IDs" }
            require(page.items.firstOrNull { it.id == detailDto.id } == detailDto.summary()) {
                "Canonical detail and catalogue summary disagree"
            }
            val hackathons = page.items.map { it.toDomain() }
            val template = detailDto.toDomain()
            val details = hackathons.associate { item ->
                item.id to if (item.id == template.hackathon.id) template else demoDetail(item, template)
            }
            require(INITIAL_SAVED_IDS.all { id -> hackathons.any { it.id == id } }) { "Missing initial saved demo event" }
            require(profile.savedCount == INITIAL_SAVED_IDS.size) { "Demo profile and saved fixture scenario disagree" }
            return DemoRepository(hackathons, details, metadata, page.asOf, profile, INITIAL_SAVED_IDS)
        }

        /** The fixture README allows additional demo details, with no invented real eligibility or links. */
        private fun demoDetail(item: Hackathon, template: HackathonDetail): HackathonDetail = HackathonDetail(
            hackathon = item,
            description = item.shortDescription,
            eligibility = Eligibility(null, null, null, null, null, emptyList()),
            stages = emptyList(),
            prizeDetails = item.prize.summary,
            submissionRequirements = null,
            judgingCriteria = null,
            officialUrl = item.organizerWebsiteUrl ?: template.officialUrl,
            registrationUrl = null,
            rulesUrl = null,
            sourceUrl = item.organizerWebsiteUrl ?: template.sourceUrl,
        )

        private fun compareMoment(a: EventMoment?, b: EventMoment?): Int {
            if (a == null) return if (b == null) 0 else 1
            if (b == null) return -1
            val date = a.date.compareTo(b.date)
            if (date != 0) return date
            if (a.time == null) return if (b.time == null) 0 else 1
            if (b.time == null) return -1
            return a.time.compareTo(b.time)
        }

        private fun validate(query: CatalogQuery) {
            fun check(ok: Boolean, field: String) {
                if (!ok) throw InvalidCatalogQueryException(field, "Invalid catalog parameter: $field")
            }
            check(query.page >= 0, "page")
            check(query.size in 1..50, "size")
            check(query.query.length <= 120, "query")
            check(query.format != EventFormat.UNSUPPORTED, "format")
            check(query.registrationStatus != RegistrationStatus.UNSUPPORTED, "registrationStatus")
            check(query.countryCode == null || Regex("^[A-Z]{2}$").matches(query.countryCode), "countryCode")
            check(query.city == null || query.city.length <= 100, "city")
            check(query.topic == null || query.topic.length <= 50, "topic")
            listOf("startsFrom" to query.startsFrom, "startsTo" to query.startsTo).forEach { (field, date) ->
                if (date != null) {
                    check(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(date), field)
                    try { LocalDate.parse(date) } catch (_: IllegalArgumentException) { check(false, field) }
                }
            }
            check(query.startsFrom == null || query.startsTo == null || query.startsFrom <= query.startsTo, "startsTo")
        }
    }
}
