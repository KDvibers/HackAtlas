package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class DemoRepositoryTest {
    private val fixtures = FixtureSource { CanonicalFixtures.files.getValue(it) }
    private suspend fun repository() = DemoRepository.load(fixtures)

    @Test
    fun firstVisualScenarioUsesExplicitNewestAndFixedClock() = runTest {
        val repo = repository()
        val page = repo.catalog(CatalogQuery(sort = CatalogSort.NEWEST))
        assertEquals(listOf("AI Sprint 2026", "Fintech Hack", "GameDev Jam"), page.items.take(3).map { it.title })
        assertEquals(DemoRepository.FIXTURE_AS_OF, page.asOf)
        assertEquals(5, page.totalElements)
        assertEquals(1, page.totalPages)
        assertFalse(page.hasNext)
    }

    @Test
    fun defaultSortUsesLocalDeadlineUnknownLastAndStableIdTieBreaker() = runTest {
        val page = repository().catalog()
        assertEquals(
            listOf("Registration Closed Demo", "GameDev Jam", "Fintech Hack", "AI Sprint 2026", "GreenTech Challenge"),
            page.items.map { it.title },
        )
    }

    @Test
    fun timeKnownSortsBeforeDateOnlyOnTheSameLocalDate() = runTest {
        val page = ContractJson.decodeFromString<HackathonPageDto>(CanonicalFixtures.files.getValue("catalog-page.json"))
        val changed = page.copy(items = page.items.mapIndexed { index, item ->
            if (index == 1) item.copy(registrationDeadline = EventTimeDto(
                "MINUTE", "2026-10-30", "10:00", "Asia/Almaty", "2026-10-30T05:00:00Z",
            )) else item
        })
        val repo = DemoRepository.load(FixtureSource { name ->
            if (name == "catalog-page.json") ContractJson.encodeToString(changed) else fixtures.readText(name)
        })
        assertEquals(listOf("Fintech Hack", "GameDev Jam"), repo.catalog().items.drop(1).take(2).map { it.title })
    }

    @Test
    fun searchUsesDocumentedFieldsAndAllFiltersAreAnded() = runTest {
        val repo = repository()
        assertEquals("Fintech Hack", repo.catalog(CatalogQuery(query = "  аСТАнА  ")).items.single().title)
        assertEquals(5, repo.catalog(CatalogQuery(query = "demo community")).totalElements)
        assertEquals("AI Sprint 2026", repo.catalog(CatalogQuery(query = "AI / ML")).items.single().title)
        assertEquals(5, repo.catalog(CatalogQuery(query = "  ")).totalElements)
        assertEquals("Fintech Hack", repo.catalog(CatalogQuery(
            format = EventFormat.OFFLINE, countryCode = "KZ", topic = "fintech",
            registrationStatus = RegistrationStatus.OPEN, individualApplicationAllowed = true,
        )).items.single().title)
        assertTrue(repo.catalog(CatalogQuery(format = EventFormat.ONLINE, countryCode = "KZ")).items.isEmpty())
        assertEquals(4, repo.catalog(CatalogQuery(individualApplicationAllowed = true)).totalElements)
        assertEquals(0, repo.catalog(CatalogQuery(individualApplicationAllowed = false)).totalElements)
    }

    @Test
    fun dateFilterUsesInclusiveStartDateAndNotEventOverlap() = runTest {
        val repo = repository()
        assertEquals("Fintech Hack", repo.catalog(CatalogQuery(startsFrom = "2026-11-20", startsTo = "2026-11-20")).items.single().title)
        // AI runs until November 14 but started on November 12, so is not an overlap match.
        assertTrue(repo.catalog(CatalogQuery(startsFrom = "2026-11-13", startsTo = "2026-11-14")).items.isEmpty())
        val byStart = repo.catalog(CatalogQuery(sort = CatalogSort.START_ASC)).items.map { it.starts!!.date }
        assertEquals(byStart.sorted(), byStart)
    }

    @Test
    fun unknownStartsDoNotMatchDateFiltersAndClosedEventsRemainAvailableById() = runTest {
        val page = ContractJson.decodeFromString<HackathonPageDto>(CanonicalFixtures.files.getValue("catalog-page.json"))
        val changed = page.copy(items = page.items.mapIndexed { index, item ->
            when (index) {
                1 -> item.copy(eventStatus = "ENDED")
                2 -> item.copy(eventStatus = "CANCELLED")
                3 -> item.copy(starts = null, ends = null, eventStatus = "POSTPONED")
                else -> item
            }
        })
        val repo = DemoRepository.load(FixtureSource { name ->
            if (name == "catalog-page.json") ContractJson.encodeToString(changed) else fixtures.readText(name)
        })
        assertEquals(3, repo.catalog().totalElements)
        assertEquals(2, repo.catalog(CatalogQuery(startsFrom = "2026-01-01")).totalElements)
        assertEquals(EventStatus.ENDED, repo.detail(page.items[1].id)?.hackathon?.eventStatus)
        assertEquals(EventStatus.CANCELLED, repo.detail(page.items[2].id)?.hackathon?.eventStatus)
        assertTrue(repo.savedHackathons().any { it.eventStatus == EventStatus.CANCELLED })
    }

    @Test
    fun paginationHasNoDuplicatesAndOutOfRangeDoesNotEraseTotals() = runTest {
        val repo = repository()
        val pages = (0..2).map { repo.catalog(CatalogQuery(page = it, size = 2)) }
        assertEquals(listOf(2, 2, 1), pages.map { it.items.size })
        assertEquals(listOf(true, true, false), pages.map { it.hasNext })
        assertEquals(5, pages.flatMap { it.items }.map { it.id }.toSet().size)
        val out = repo.catalog(CatalogQuery(page = Int.MAX_VALUE, size = 50))
        assertTrue(out.items.isEmpty())
        assertEquals(5, out.totalElements)
        assertEquals(1, out.totalPages)
        assertFalse(out.hasNext)
        val empty = repo.catalog(CatalogQuery(query = "no matching hackathon"))
        assertEquals(0, empty.totalPages)
        assertEquals(0, empty.totalElements)
        assertFalse(empty.hasNext)
    }

    @Test
    fun invalidQueryIsRejectedRatherThanSilentlyClamped() = runTest {
        val repo = repository()
        listOf(
            CatalogQuery(page = -1), CatalogQuery(size = 0), CatalogQuery(size = 51),
            CatalogQuery(startsFrom = "2026-12-01", startsTo = "2026-11-01"),
            CatalogQuery(startsFrom = "2026-02-30"), CatalogQuery(startsFrom = "2026-1-1"),
            CatalogQuery(countryCode = "kz"), CatalogQuery(query = "x".repeat(121)),
            CatalogQuery(format = EventFormat.UNSUPPORTED),
        ).forEach { query -> assertFailsWith<InvalidCatalogQueryException> { repo.catalog(query) } }
    }

    @Test
    fun bookmarksAreSharedIdempotentAndOnlyInMemory() = runTest {
        val repo = repository()
        assertEquals(listOf("AI Sprint 2026", "GameDev Jam", "GreenTech Challenge"), repo.savedHackathons().map { it.title })
        assertEquals(3, repo.profile.value.savedCount)
        val fintech = repo.catalog(CatalogQuery(topic = "fintech")).items.single()
        repo.setSaved(fintech.id, true)
        repo.setSaved(fintech.id, true)
        assertEquals(4, repo.savedIds.value.size)
        assertEquals(4, repo.profile.value.savedCount)
        assertEquals(fintech, repo.savedHackathons().first())
        assertEquals(fintech, repo.detail(fintech.id)?.hackathon)
        repo.toggleSaved(fintech.id)
        assertFalse(fintech.id in repo.savedIds.value)
        assertEquals(3, repo.profile.value.savedCount)
        val ai = repo.savedHackathons().first().id
        repo.setSaved(ai, false)
        repo.setSaved(ai, false)
        assertEquals(2, repo.profile.value.savedCount)
        assertEquals(3, repository().profile.value.savedCount) // A new demo instance starts from fixtures.
        assertFailsWith<IllegalArgumentException> { repo.setSaved("unknown", true) }
    }

    @Test
    fun allCardsHaveSafeDemoDetailsAndUnknownIdsAreNotInvented() = runTest {
        val repo = repository()
        repo.catalog().items.forEach { item ->
            val detail = assertNotNull(repo.detail(item.id))
            assertEquals(item, detail.hackathon)
            assertTrue(detail.hackathon.isDemo)
            if (item.artwork != Artwork.AI_BUST) {
                assertNull(detail.registrationUrl)
                assertNull(detail.eligibility.allowedCountryCodes)
            }
        }
        assertNull(repo.detail("unknown"))
    }

    @Test
    fun demoLoaderFailureNeverBecomesFakeSuccess() = runTest {
        assertFailsWith<IllegalStateException> {
            DemoRepository.load(FixtureSource { error("Missing fixture") })
        }
    }
}
