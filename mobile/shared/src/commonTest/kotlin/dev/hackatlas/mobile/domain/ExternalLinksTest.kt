package dev.hackatlas.mobile.domain

import dev.hackatlas.mobile.data.*
import kotlin.test.*

class ExternalLinksTest {
    private val seed = ContractJson.decodeFromString<HackathonDetailDto>(
        CanonicalFixtures.files.getValue("hackathon-detail.json")).toDomain()
    private val real = seed.copy(hackathon = seed.hackathon.copy(isDemo = false,
        registrationStatus = RegistrationStatus.OPEN, eventStatus = EventStatus.SCHEDULED),
        officialUrl = "https://events.example.org/hack?lang=ru#about",
        registrationUrl = "https://register.example.org/apply?id=42",
        sourceUrl = "https://source.example.org/event")

    @Test fun acceptsAbsoluteHttpsAndPreservesPathQueryFragment() {
        val raw = "https://events.example.org:8443/a%20b?lang=ru&track=1#apply"
        assertEquals(raw, SafeHttpsUrl.parse(raw)?.value)
        assertNotNull(SafeHttpsUrl.parse("HTTPS://EVENTS.EXAMPLE.ORG/a"))
    }

    @Test fun rejectsUnsafeAndMalformedUrls() {
        for (raw in listOf(null, "", " https://host.org", "http://host.org", "javascript:alert(1)",
            "intent://host.org", "file:///tmp/x", "//host.org", "https:///path", "https://",
            "https://user:pass@host.org", "https://@host.org", "https://host.org\\@evil.org",
            "https://host.org/a b", "https://host.org/\npath", "https://host.org/\u0000",
            "https://%65vil.org", "https://host.org:0/x", "https://host.org:65536/x",
            "https://host.org:abc", "https://host..org", "https://host.org/%0d%0aInjected",
            "https://host.org/%ZZ")) {
            assertNull(SafeHttpsUrl.parse(raw), "Must reject $raw")
        }
    }

    @Test fun openRealEventUsesOnlyRegistrationLink() {
        val policy = eventLinkPolicy(real)
        assertEquals(RegistrationActionState.AVAILABLE, policy.registrationState)
        assertEquals(real.registrationUrl, policy.registrationUrl?.value)
        assertEquals(real.officialUrl, policy.officialUrl?.value)
        assertEquals(real.officialUrl, policy.shareUrl?.value)
    }

    @Test fun unavailableRegistrationDoesNotFallBackToOfficialSite() {
        for (link in listOf(null, "http://unsafe.org", "javascript:alert(1)")) {
            val policy = eventLinkPolicy(real.copy(registrationUrl = link))
            assertEquals(RegistrationActionState.UNAVAILABLE, policy.registrationState)
            assertNull(policy.registrationUrl)
            assertNotNull(policy.officialUrl)
            assertNotNull(policy.shareUrl)
        }
    }

    @Test fun closedCancelledEndedAndUnknownAreNotActiveRegistration() {
        for (status in RegistrationStatus.entries.filter { it != RegistrationStatus.OPEN }) {
            val policy = eventLinkPolicy(real.copy(hackathon = real.hackathon.copy(registrationStatus = status)))
            assertNull(policy.registrationUrl)
            assertNotEquals(RegistrationActionState.AVAILABLE, policy.registrationState)
            assertNotNull(policy.shareUrl)
        }
        for ((status, expected) in listOf(EventStatus.CANCELLED to RegistrationActionState.CANCELLED,
            EventStatus.ENDED to RegistrationActionState.ENDED, EventStatus.UNSUPPORTED to RegistrationActionState.UNAVAILABLE)) {
            val policy = eventLinkPolicy(real.copy(hackathon = real.hackathon.copy(eventStatus = status)))
            assertEquals(expected, policy.registrationState)
            assertNull(policy.registrationUrl)
        }
    }

    @Test fun postponedAndOngoingKeepServerOpenStatus() {
        for (status in listOf(EventStatus.POSTPONED, EventStatus.ONGOING)) {
            assertEquals(RegistrationActionState.AVAILABLE,
                eventLinkPolicy(real.copy(hackathon = real.hackathon.copy(eventStatus = status))).registrationState)
        }
    }

    @Test fun demoIsDetectedForBothSourceTypes() {
        for (policy in listOf(eventLinkPolicy(real, bundled = true),
            eventLinkPolicy(real.copy(hackathon = real.hackathon.copy(isDemo = true))))) {
            assertTrue(policy.isDemo)
            assertEquals(RegistrationActionState.DEMO_PREVIEW, policy.registrationState)
            assertNotNull(policy.registrationUrl)
            assertTrue(assertNotNull(policy.shareEvent("Test", "DEMO — fictional event")).text.startsWith("DEMO — fictional event\n"))
        }
    }

    @Test fun demoClosedStillHasNoRegistrationAction() {
        val policy = eventLinkPolicy(real.copy(hackathon = real.hackathon.copy(isDemo = true,
            registrationStatus = RegistrationStatus.CLOSED)))
        assertEquals(RegistrationActionState.CLOSED, policy.registrationState)
        assertNull(policy.registrationUrl)
    }

    @Test fun shareFallsBackToSourceButNeverToRegistration() {
        val noOfficial = real.copy(officialUrl = "javascript:alert(1)")
        assertEquals(real.sourceUrl, eventLinkPolicy(noOfficial).shareUrl?.value)
        val noLinks = eventLinkPolicy(noOfficial.copy(sourceUrl = "http://unsafe.org"))
        assertNull(noLinks.shareEvent(real.hackathon.title, "DEMO"))
        assertNotNull(noLinks.registrationUrl)
    }

    @Test fun realShareContainsExactlyTitleAndPublicUrl() {
        val payload = assertNotNull(eventLinkPolicy(real).shareEvent("AI Sprint", "DEMO"))
        assertEquals("AI Sprint", payload.subject)
        assertEquals("AI Sprint\n${real.officialUrl}", payload.text)
        assertFalse(payload.text.contains(real.registrationUrl!!))
    }
}
