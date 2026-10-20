package com.hackatlas.catalog;

import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
class CatalogMapper {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<CatalogDtos.Stage>> STAGES = new TypeReference<>() { };
    private final JsonMapper json;

    CatalogMapper(JsonMapper json) {
        this.json = json;
    }

    CatalogDtos.HackathonSummary summary(CatalogHackathon h, String registrationStatus, String eventStatus) {
        return new CatalogDtos.HackathonSummary(
                h.id, h.slug, h.title, h.shortDescription,
                new CatalogDtos.Organizer(h.organizerName, h.organizerWebsiteUrl),
                h.format, new CatalogDtos.Location(h.countryCode, h.city, h.venue),
                eventTime(h.starts), eventTime(h.ends), eventTime(h.registrationDeadline),
                registrationStatus, eventStatus,
                h.topics.stream().map(t -> new CatalogDtos.Tag(t.id, t.label)).toList(),
                new CatalogDtos.Prize(h.prizeStatus, h.prizeTotalAmount, h.prizeCurrency, h.prizeKind, h.prizeSummary),
                new CatalogDtos.TeamRules(h.teamMinSize, h.teamMaxSize, h.individualApplicationAllowed,
                        h.soloParticipationAllowed, h.teamFormationAvailable),
                h.imageUrl, h.artworkKey, h.lastVerifiedAt, h.updatedAt, h.demo
        );
    }

    CatalogDtos.HackathonDetail detail(CatalogHackathon h, String registrationStatus, String eventStatus) {
        var s = summary(h, registrationStatus, eventStatus);
        return new CatalogDtos.HackathonDetail(
                s.id(), s.slug(), s.title(), s.shortDescription(), s.organizer(), s.format(), s.location(),
                s.starts(), s.ends(), s.registrationDeadline(), s.registrationStatus(), s.eventStatus(),
                s.topics(), s.prize(), s.team(), s.imageUrl(), s.artworkKey(), s.lastVerifiedAt(), s.updatedAt(), s.isDemo(),
                h.description,
                new CatalogDtos.Eligibility(h.eligibilitySummary,
                        h.allowedCountryCodes == null ? null : json.readValue(h.allowedCountryCodes, STRINGS),
                        h.minAge, h.maxAge, h.studentsOnly, json.readValue(h.languages, STRINGS)),
                json.readValue(h.stages, STAGES),
                h.prizeDetails, h.submissionRequirements, h.judgingCriteria,
                h.officialUrl, h.registrationUrl, h.rulesUrl, h.sourceUrl
        );
    }

    private static CatalogDtos.EventTime eventTime(CatalogEventTime value) {
        return value == null ? null : value.toDto();
    }
}
