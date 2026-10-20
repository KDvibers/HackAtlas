package dev.hackatlas.mobile.data

import dev.hackatlas.mobile.domain.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class ContractParsingTest {
    private val catalog: HackathonPageDto
        get() = ContractJson.decodeFromString(CanonicalFixtures.files.getValue("catalog-page.json"))

    @Test
    fun everyCanonicalManifestExampleDeserializes() {
        val manifest = ContractJson.parseToJsonElement(CanonicalFixtures.files.getValue("manifest.json")).jsonObject
        assertEquals(6, manifest.size)
        manifest.forEach { (name, schema) ->
            val input = CanonicalFixtures.files.getValue(name)
            when (schema.jsonPrimitive.content) {
                "HackathonPage" -> ContractJson.decodeFromString<HackathonPageDto>(input)
                "HackathonDetail" -> ContractJson.decodeFromString<HackathonDetailDto>(input)
                "FilterMetadata" -> ContractJson.decodeFromString<FilterMetadataDto>(input)
                "Profile" -> ContractJson.decodeFromString<ProfileDto>(input)
                "Problem" -> ContractJson.decodeFromString<ProblemDto>(input)
                else -> fail("Add a parsing test for canonical schema ${schema.jsonPrimitive.content}")
            }
        }
    }

    @Test
    fun canonicalDetailKeepsTheCatalogueIdentityAndExactValues() {
        val detail = ContractJson.decodeFromString<HackathonDetailDto>(CanonicalFixtures.files.getValue("hackathon-detail.json"))
        assertEquals(catalog.items.first(), detail.summary())
        val mapped = detail.toDomain()
        assertEquals("10000.00", mapped.hackathon.prize.totalAmount)
        assertEquals("2026-11-01T13:00:00Z", mapped.hackathon.registrationDeadline?.instant)
        assertEquals(emptyList(), mapped.eligibility.allowedCountryCodes)
        assertEquals(false, mapped.eligibility.studentsOnly)
        assertTrue(mapped.hackathon.contractWarnings.isEmpty())
    }

    @Test
    fun nullableAndDateOnlyValuesAreNotInvented() {
        val dateOnly = catalog.items[1].toDomain()
        assertEquals(TimePrecision.DATE, dateOnly.starts?.precision)
        assertNull(dateOnly.starts?.time)
        assertNull(dateOnly.starts?.instant)
        val unknown = catalog.items[3].toDomain()
        assertEquals(PrizeStatus.UNKNOWN, unknown.prize.status)
        assertNull(unknown.prize.totalAmount)
        assertNull(unknown.team.individualApplicationAllowed)
        assertNull(unknown.registrationDeadline)
    }

    @Test
    fun unknownResponseFieldsAreIgnoredButRequiredNullableFieldsRemainRequired() {
        val original = ContractJson.parseToJsonElement(ContractJson.encodeToString(catalog.items.first())).jsonObject
        val extended = JsonObject(original + ("futureField" to JsonPrimitive(true)))
        assertEquals(catalog.items.first(), ContractJson.decodeFromString<HackathonSummaryDto>(extended.toString()))
        val missing = JsonObject(original - "imageUrl")
        assertFailsWith<SerializationException> { ContractJson.decodeFromString<HackathonSummaryDto>(missing.toString()) }
    }

    @Test
    fun futureEnumValuesProduceSafeStateAndDiagnostics() {
        val item = catalog.items.first()
        val mapped = item.copy(
            format = "NEW_FORMAT", registrationStatus = "PAUSED", eventStatus = "NEW_STATUS",
            artworkKey = "NEW_ART", prize = item.prize.copy(kind = "TOKENS", status = "NEW_PRIZE"),
            starts = item.starts!!.copy(precision = "SECOND"),
        ).toDomain()
        assertEquals(EventFormat.UNSUPPORTED, mapped.format)
        assertEquals(RegistrationStatus.UNSUPPORTED, mapped.registrationStatus)
        assertEquals(EventStatus.UNSUPPORTED, mapped.eventStatus)
        assertEquals(Artwork.WIREFRAME, mapped.artwork)
        assertEquals(PrizeKind.UNSUPPORTED, mapped.prize.kind)
        assertEquals(PrizeStatus.UNSUPPORTED, mapped.prize.status)
        assertEquals(TimePrecision.UNSUPPORTED, mapped.starts?.precision)
        assertNull(mapped.starts?.time)
        assertNull(mapped.starts?.instant)
        assertEquals(7, mapped.contractWarnings.size)
    }

    @Test
    fun aFalseExactTimeIsRejectedInsteadOfBeingRendered() {
        val item = catalog.items.first()
        assertFailsWith<IllegalArgumentException> {
            item.copy(starts = item.starts!!.copy(instant = "2026-11-12T10:00:00Z")).toDomain()
        }
        assertFailsWith<IllegalArgumentException> {
            item.copy(starts = item.starts!!.copy(precision = "DATE")).toDomain()
        }
    }

    @Test
    fun profileInterestIdsResolveThroughMetadata() {
        val metadata = ContractJson.decodeFromString<FilterMetadataDto>(CanonicalFixtures.files.getValue("filter-metadata.json")).toDomain()
        val profile = ContractJson.decodeFromString<ProfileDto>(CanonicalFixtures.files.getValue("profile.json")).toDemoDomain(metadata)
        assertEquals(listOf("Java", "Kotlin", "Backend", "AI / ML"), profile.interests.map { it.label })
        assertEquals(profile.interests.size, profile.interestsCount)
        assertTrue(profile.isDemo)
    }
}
