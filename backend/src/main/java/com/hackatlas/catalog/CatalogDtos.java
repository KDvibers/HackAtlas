package com.hackatlas.catalog;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Explicit wire models: persistence entities are never serialized by the API. */
public final class CatalogDtos {
    private CatalogDtos() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Tag(String id, String label) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Organizer(String name, String websiteUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Location(String countryCode, String city, String venue) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EventTime(
            String precision,
            LocalDate date,
            @JsonFormat(pattern = "HH:mm") LocalTime time,
            String timeZone,
            Instant instant
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Prize(
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
            String currency,
            String kind,
            String summary
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TeamRules(
            Integer minSize,
            Integer maxSize,
            Boolean individualApplicationAllowed,
            Boolean soloParticipationAllowed,
            Boolean teamFormationAvailable
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Eligibility(
            String summary,
            List<String> allowedCountryCodes,
            Integer minAge,
            Integer maxAge,
            Boolean studentsOnly,
            List<String> languages
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stage(String title, EventTime when, String description) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HackathonSummary(
            UUID id, String slug, String title, String shortDescription,
            Organizer organizer, String format, Location location,
            EventTime starts, EventTime ends, EventTime registrationDeadline,
            String registrationStatus, String eventStatus, List<Tag> topics,
            Prize prize, TeamRules team, String imageUrl, String artworkKey,
            Instant lastVerifiedAt, Instant updatedAt, boolean isDemo
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HackathonDetail(
            UUID id, String slug, String title, String shortDescription,
            Organizer organizer, String format, Location location,
            EventTime starts, EventTime ends, EventTime registrationDeadline,
            String registrationStatus, String eventStatus, List<Tag> topics,
            Prize prize, TeamRules team, String imageUrl, String artworkKey,
            Instant lastVerifiedAt, Instant updatedAt, boolean isDemo,
            String description, Eligibility eligibility, List<Stage> stages,
            String prizeDetails, String submissionRequirements, String judgingCriteria,
            String officialUrl, String registrationUrl, String rulesUrl, String sourceUrl
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HackathonPage(
            List<HackathonSummary> items, BigInteger page, int size,
            long totalElements, long totalPages, boolean hasNext, Instant asOf
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Format(String id, String label) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Country(String code, String label) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record City(String countryCode, String name) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FilterMetadata(
            List<Tag> topics, List<Format> formats, List<Country> countries,
            List<City> cities, List<Tag> interests
    ) {
    }
}
