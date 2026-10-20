package com.hackatlas.catalog;

import java.io.IOException;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit development fixtures only. The production migration contains no seed.
 * Repeated calls insert missing IDs without overwriting existing catalogue edits.
 */
@Component
@Profile("(dev | test) & !prod")
@ConditionalOnProperty(name = "hackatlas.demo.enabled", havingValue = "true", matchIfMissing = false)
@Transactional
public class CatalogDemoSeeder implements ApplicationRunner {
    private final EntityManager entityManager;
    private final JsonMapper json;

    CatalogDemoSeeder(EntityManager entityManager, JsonMapper json) {
        this.entityManager = entityManager;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        var page = read("demo/catalog-page.json", CatalogDtos.HackathonPage.class);
        var detail = read("demo/hackathon-detail.json", CatalogDtos.HackathonDetail.class);
        var metadata = read("demo/filter-metadata.json", CatalogDtos.FilterMetadata.class);
        if (!detail.isDemo() || page.items().stream().anyMatch(item -> !item.isDemo())) {
            throw new IllegalStateException("The demo seed must contain only explicit DEMO events");
        }
        for (var interest : metadata.interests()) {
            ensureTag(interest, true);
        }
        for (var topic : metadata.topics()) {
            ensureTag(topic, metadata.interests().stream().anyMatch(interest -> interest.id().equals(topic.id())));
        }
        for (int index = 0; index < page.items().size(); index++) {
            var summary = page.items().get(index);
            if (entityManager.find(CatalogHackathon.class, summary.id()) != null) {
                continue;
            }
            var h = fromSummary(summary);
            // The canonical page is ordered by NEWEST; preserve its order without
            // changing public updatedAt values or overwriting existing records.
            h.publishedAt = page.asOf().minusSeconds(index);
            if (summary.id().equals(detail.id())) {
                applyDetail(h, detail);
            }
            entityManager.persist(h);
        }
        entityManager.flush();
    }

    private CatalogHackathon fromSummary(CatalogDtos.HackathonSummary s) {
        var h = new CatalogHackathon();
        h.id = s.id();
        h.slug = s.slug();
        h.title = s.title();
        h.shortDescription = s.shortDescription();
        h.organizerName = s.organizer().name();
        h.organizerWebsiteUrl = s.organizer().websiteUrl();
        h.format = s.format();
        h.countryCode = s.location().countryCode();
        h.city = s.location().city();
        h.venue = s.location().venue();
        h.starts = CatalogEventTime.from(s.starts());
        h.ends = CatalogEventTime.from(s.ends());
        h.registrationDeadline = CatalogEventTime.from(s.registrationDeadline());
        h.registrationStatus = s.registrationStatus();
        h.eventStatus = s.eventStatus();
        h.publicationStatus = "PUBLISHED";
        for (var tag : s.topics()) {
            h.topics.add(ensureTag(tag, true));
        }
        h.prizeStatus = s.prize().status();
        h.prizeTotalAmount = s.prize().totalAmount();
        h.prizeCurrency = s.prize().currency();
        h.prizeKind = s.prize().kind();
        h.prizeSummary = s.prize().summary();
        h.teamMinSize = s.team().minSize();
        h.teamMaxSize = s.team().maxSize();
        h.individualApplicationAllowed = s.team().individualApplicationAllowed();
        h.soloParticipationAllowed = s.team().soloParticipationAllowed();
        h.teamFormationAvailable = s.team().teamFormationAvailable();
        h.imageUrl = s.imageUrl();
        h.artworkKey = s.artworkKey();
        h.lastVerifiedAt = s.lastVerifiedAt();
        h.updatedAt = s.updatedAt();
        h.demo = s.isDemo();

        // The page fixture has no detail fields for these events. Keep unknown
        // optional values unknown; do not copy AI Sprint's rules to other events.
        h.description = s.shortDescription();
        h.languages = json.writeValueAsString(List.of());
        h.stages = json.writeValueAsString(List.of());
        h.officialUrl = s.organizer().websiteUrl();
        h.sourceUrl = s.organizer().websiteUrl();
        if (h.officialUrl == null) {
            throw new IllegalStateException("DEMO summary requires an explicit source URL");
        }
        return h;
    }

    private void applyDetail(CatalogHackathon h, CatalogDtos.HackathonDetail detail) {
        h.description = detail.description();
        var eligibility = detail.eligibility();
        h.eligibilitySummary = eligibility.summary();
        h.allowedCountryCodes = eligibility.allowedCountryCodes() == null
                ? null : json.writeValueAsString(eligibility.allowedCountryCodes());
        h.minAge = eligibility.minAge();
        h.maxAge = eligibility.maxAge();
        h.studentsOnly = eligibility.studentsOnly();
        h.languages = json.writeValueAsString(eligibility.languages());
        for (var stage : detail.stages()) {
            CatalogEventTime.from(stage.when());
        }
        h.stages = json.writeValueAsString(detail.stages());
        h.prizeDetails = detail.prizeDetails();
        h.submissionRequirements = detail.submissionRequirements();
        h.judgingCriteria = detail.judgingCriteria();
        h.officialUrl = detail.officialUrl();
        h.registrationUrl = detail.registrationUrl();
        h.rulesUrl = detail.rulesUrl();
        h.sourceUrl = detail.sourceUrl();
    }

    private CatalogTag ensureTag(CatalogDtos.Tag tag, boolean interestAllowed) {
        CatalogTag current = entityManager.find(CatalogTag.class, tag.id());
        if (current == null) {
            current = new CatalogTag(tag.id(), tag.label(), interestAllowed);
            entityManager.persist(current);
        }
        return current;
    }

    private <T> T read(String path, Class<T> type) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return json.readValue(input, type);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read canonical demo fixture: " + path, exception);
        }
    }
}
