package com.hackatlas.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_hackathon", schema = "hackatlas")
class CatalogHackathon {
    @Id
    UUID id;
    @Column(nullable = false, unique = true, length = 200)
    String slug;
    @Column(nullable = false, length = 200)
    String title;
    @Column(name = "short_description", nullable = false, length = 350)
    String shortDescription;
    @Column(name = "organizer_name", nullable = false, length = 160)
    String organizerName;
    @Column(name = "organizer_website_url", columnDefinition = "text")
    String organizerWebsiteUrl;
    @Column(nullable = false, length = 10)
    String format;
    @Column(name = "country_code", length = 2)
    String countryCode;
    @Column(length = 100)
    String city;
    @Column(length = 300)
    String venue;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "precision", column = @Column(name = "starts_precision", length = 6)),
            @AttributeOverride(name = "date", column = @Column(name = "starts_date")),
            @AttributeOverride(name = "time", column = @Column(name = "starts_time")),
            @AttributeOverride(name = "timeZone", column = @Column(name = "starts_time_zone", columnDefinition = "text")),
            @AttributeOverride(name = "instant", column = @Column(name = "starts_instant"))
    })
    CatalogEventTime starts;
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "precision", column = @Column(name = "ends_precision", length = 6)),
            @AttributeOverride(name = "date", column = @Column(name = "ends_date")),
            @AttributeOverride(name = "time", column = @Column(name = "ends_time")),
            @AttributeOverride(name = "timeZone", column = @Column(name = "ends_time_zone", columnDefinition = "text")),
            @AttributeOverride(name = "instant", column = @Column(name = "ends_instant"))
    })
    CatalogEventTime ends;
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "precision", column = @Column(name = "deadline_precision", length = 6)),
            @AttributeOverride(name = "date", column = @Column(name = "deadline_date")),
            @AttributeOverride(name = "time", column = @Column(name = "deadline_time")),
            @AttributeOverride(name = "timeZone", column = @Column(name = "deadline_time_zone", columnDefinition = "text")),
            @AttributeOverride(name = "instant", column = @Column(name = "deadline_instant"))
    })
    CatalogEventTime registrationDeadline;

    @Column(name = "registration_status", nullable = false, length = 10)
    String registrationStatus;
    @Column(name = "event_status", nullable = false, length = 10)
    String eventStatus;
    @Column(name = "publication_status", nullable = false, length = 10)
    String publicationStatus;
    @Column(name = "published_at")
    Instant publishedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "catalog_hackathon_topic", schema = "hackatlas",
            joinColumns = @JoinColumn(name = "hackathon_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @OrderBy("id ASC")
    Set<CatalogTag> topics = new LinkedHashSet<>();

    @Column(name = "prize_status", nullable = false, length = 10)
    String prizeStatus;
    @Column(name = "prize_total_amount", columnDefinition = "numeric")
    BigDecimal prizeTotalAmount;
    @Column(name = "prize_currency", length = 3)
    String prizeCurrency;
    @Column(name = "prize_kind", nullable = false, length = 12)
    String prizeKind;
    @Column(name = "prize_summary", length = 500)
    String prizeSummary;

    @Column(name = "team_min_size")
    Integer teamMinSize;
    @Column(name = "team_max_size")
    Integer teamMaxSize;
    @Column(name = "individual_application_allowed")
    Boolean individualApplicationAllowed;
    @Column(name = "solo_participation_allowed")
    Boolean soloParticipationAllowed;
    @Column(name = "team_formation_available")
    Boolean teamFormationAvailable;

    @Column(name = "image_url", columnDefinition = "text")
    String imageUrl;
    @Column(name = "artwork_key", nullable = false, length = 20)
    String artworkKey;
    @Column(name = "last_verified_at")
    Instant lastVerifiedAt;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;
    @Column(name = "is_demo", nullable = false)
    boolean demo;
    @Column(nullable = false, columnDefinition = "text")
    String description;
    @Column(name = "eligibility_summary", columnDefinition = "text")
    String eligibilitySummary;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_country_codes", columnDefinition = "jsonb")
    String allowedCountryCodes;
    @Column(name = "min_age")
    Integer minAge;
    @Column(name = "max_age")
    Integer maxAge;
    @Column(name = "students_only")
    Boolean studentsOnly;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    String languages;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    String stages;
    @Column(name = "prize_details", columnDefinition = "text")
    String prizeDetails;
    @Column(name = "submission_requirements", columnDefinition = "text")
    String submissionRequirements;
    @Column(name = "judging_criteria", columnDefinition = "text")
    String judgingCriteria;
    @Column(name = "official_url", nullable = false, columnDefinition = "text")
    String officialUrl;
    @Column(name = "registration_url", columnDefinition = "text")
    String registrationUrl;
    @Column(name = "rules_url", columnDefinition = "text")
    String rulesUrl;
    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    String sourceUrl;

    protected CatalogHackathon() {
    }
}
