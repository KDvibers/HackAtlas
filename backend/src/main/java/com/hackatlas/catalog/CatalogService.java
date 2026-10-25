package com.hackatlas.catalog;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogService {
    private final EntityManager entityManager;
    private final CatalogMapper mapper;
    private final Clock clock;

    CatalogService(EntityManager entityManager, CatalogMapper mapper, Clock clock) {
        this.entityManager = entityManager;
        this.mapper = mapper;
        this.clock = clock;
    }

    public CatalogDtos.HackathonPage list(CatalogFilter filter) {
        Instant asOf = clock.instant();
        // PostgreSQL text cannot contain U+0000, so these literal filters cannot
        // match any stored value. Preserve the API's string domain without an
        // invalid database text bind or a new validation restriction.
        if (containsNul(filter.query()) || containsNul(filter.city()) || containsNul(filter.topic())) {
            return new CatalogDtos.HackathonPage(List.of(), filter.page(), filter.size(), 0, 0, false, asOf);
        }
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("asOf", asOf);
        String where = where(filter, parameters);
        long count = ((Number) nativeQuery(
                CatalogStatusPolicy.CTE + "SELECT count(*) FROM effective_catalog h WHERE " + where,
                parameters).getSingleResult()).longValue();
        long totalPages = count / filter.size() + (count % filter.size() == 0 ? 0 : 1);
        BigInteger offset = filter.page().multiply(BigInteger.valueOf(filter.size()));
        if (offset.compareTo(BigInteger.valueOf(count)) >= 0) {
            return new CatalogDtos.HackathonPage(List.of(), filter.page(), filter.size(), count, totalPages, false, asOf);
        }

        // Native LIMIT/OFFSET avoids JPA's int-only offset. Count bounds the BigInteger
        // offset before conversion; a huge valid page simply returns an empty page.
        String sql = CatalogStatusPolicy.CTE + """
                SELECT h.id, h.effective_registration_status, h.effective_event_status
                FROM effective_catalog h WHERE
                """ + where + " ORDER BY " + order(filter.sort()) + " LIMIT :limit OFFSET :offset";
        var pageParameters = new LinkedHashMap<>(parameters);
        pageParameters.put("limit", filter.size());
        pageParameters.put("offset", offset.longValueExact());
        List<Object[]> rows = rows(nativeQuery(sql, pageParameters));
        List<UUID> ids = rows.stream().map(row -> (UUID) row[0]).toList();
        Map<UUID, CatalogHackathon> entities = fetchWithTopics(ids);
        List<CatalogDtos.HackathonSummary> items = rows.stream()
                .map(row -> mapper.summary(entities.get((UUID) row[0]), (String) row[1], (String) row[2]))
                .toList();
        boolean hasNext = offset.add(BigInteger.valueOf(items.size())).compareTo(BigInteger.valueOf(count)) < 0;
        return new CatalogDtos.HackathonPage(items, filter.page(), filter.size(), count, totalPages, hasNext, asOf);
    }

    public Optional<CatalogDtos.HackathonDetail> detail(UUID id) {
        var query = nativeQuery(CatalogStatusPolicy.CTE + """
                SELECT h.id, h.effective_registration_status, h.effective_event_status
                FROM effective_catalog h
                WHERE h.id = :id AND h.publication_status = 'PUBLISHED'
                """, Map.of("asOf", clock.instant(), "id", id));
        List<Object[]> rows = rows(query);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        return Optional.of(mapper.detail(fetchWithTopics(List.of(id)).get(id), (String) row[1], (String) row[2]));
    }

    public CatalogDtos.FilterMetadata metadata() {
        Map<String, Object> parameters = Map.of("asOf", clock.instant());
        String visible = " FROM effective_catalog h WHERE " + CatalogStatusPolicy.ACTIVE;
        List<CatalogDtos.Tag> topics = rows(nativeQuery(CatalogStatusPolicy.CTE + """
                SELECT DISTINCT t.id, t.label
                FROM effective_catalog h
                JOIN hackatlas.catalog_hackathon_topic ht ON ht.hackathon_id = h.id
                JOIN hackatlas.catalog_tag t ON t.id = ht.tag_id
                WHERE
                """ + CatalogStatusPolicy.ACTIVE + " ORDER BY t.id", parameters)).stream()
                .map(row -> new CatalogDtos.Tag((String) row[0], (String) row[1])).toList();
        List<CatalogDtos.Format> formats = scalars(nativeQuery(CatalogStatusPolicy.CTE
                + "SELECT DISTINCT h.format" + visible + " ORDER BY h.format", parameters)).stream()
                .map(format -> new CatalogDtos.Format(format, switch (format) {
                    case "ONLINE" -> "Онлайн";
                    case "OFFLINE" -> "Очно";
                    case "HYBRID" -> "Смешанный";
                    default -> throw new IllegalStateException("Unsupported stored event format");
                })).toList();
        List<CatalogDtos.Country> countries = scalars(nativeQuery(CatalogStatusPolicy.CTE
                + "SELECT DISTINCT h.country_code" + visible
                + " AND h.country_code IS NOT NULL ORDER BY h.country_code", parameters)).stream()
                .map(code -> new CatalogDtos.Country(code,
                        new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.forLanguageTag("ru"))))
                .toList();
        List<CatalogDtos.City> cities = rows(nativeQuery(CatalogStatusPolicy.CTE
                + "SELECT DISTINCT h.country_code, h.city" + visible
                + " AND h.country_code IS NOT NULL AND h.city IS NOT NULL ORDER BY h.country_code, h.city",
                parameters)).stream()
                .map(row -> new CatalogDtos.City((String) row[0], (String) row[1])).toList();
        List<CatalogDtos.Tag> interests = entityManager.createQuery(
                        "select t from CatalogTag t where t.interestAllowed = true order by t.id", CatalogTag.class)
                .getResultList().stream().map(tag -> new CatalogDtos.Tag(tag.id, tag.label)).toList();
        return new CatalogDtos.FilterMetadata(topics, formats, countries, cities, interests);
    }

    private Map<UUID, CatalogHackathon> fetchWithTopics(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Exactly one bounded collection fetch after the SQL page, never a paginated fetch join.
        return entityManager.createQuery("""
                        select distinct h from CatalogHackathon h
                        left join fetch h.topics
                        where h.id in :ids
                        """, CatalogHackathon.class)
                .setParameter("ids", ids).getResultList().stream()
                .collect(Collectors.toMap(h -> h.id, Function.identity()));
    }

    private Query nativeQuery(String sql, Map<String, Object> parameters) {
        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        return query;
    }

    private static String where(CatalogFilter filter, Map<String, Object> parameters) {
        var conditions = new ArrayList<String>();
        conditions.add(CatalogStatusPolicy.ACTIVE);
        if (filter.query() != null && !filter.query().isBlank()) {
            conditions.add("""
                    (
                        lower(h.title) LIKE :search ESCAPE '!'
                        OR lower(h.organizer_name) LIKE :search ESCAPE '!'
                        OR lower(h.city) LIKE :search ESCAPE '!'
                        OR EXISTS (
                            SELECT 1 FROM hackatlas.catalog_hackathon_topic st
                            JOIN hackatlas.catalog_tag t ON t.id = st.tag_id
                            WHERE st.hackathon_id = h.id AND lower(t.label) LIKE :search ESCAPE '!'
                        )
                    )
                    """);
            parameters.put("search", "%" + escapeLike(filter.query().toLowerCase(Locale.ROOT)) + "%");
        }
        add(conditions, parameters, "h.format = :format", "format", filter.format());
        add(conditions, parameters, "h.country_code = :countryCode", "countryCode", filter.countryCode());
        add(conditions, parameters, "lower(h.city) = lower(:city)", "city", filter.city());
        add(conditions, parameters, """
                EXISTS (SELECT 1 FROM hackatlas.catalog_hackathon_topic ft
                    WHERE ft.hackathon_id = h.id AND ft.tag_id = :topic)
                """, "topic", filter.topic());
        add(conditions, parameters, "h.effective_registration_status = :registrationStatus",
                "registrationStatus", filter.registrationStatus());
        add(conditions, parameters, "h.individual_application_allowed = :individual",
                "individual", filter.individualApplicationAllowed());
        add(conditions, parameters, "h.starts_date >= :startsFrom", "startsFrom", filter.startsFrom());
        add(conditions, parameters, "h.starts_date <= :startsTo", "startsTo", filter.startsTo());
        return String.join(" AND ", conditions);
    }

    private static void add(List<String> conditions, Map<String, Object> parameters,
            String condition, String name, Object value) {
        if (value != null) {
            conditions.add(condition);
            parameters.put(name, value);
        }
    }

    private static String order(String sort) {
        return switch (sort == null ? "DEADLINE_ASC" : sort) {
            case "DEADLINE_ASC" -> "h.deadline_date ASC NULLS LAST, h.deadline_time ASC NULLS LAST, h.id ASC";
            case "START_ASC" -> "h.starts_date ASC NULLS LAST, h.starts_time ASC NULLS LAST, h.id ASC";
            case "NEWEST" -> "h.published_at DESC, h.id ASC";
            default -> throw new IllegalArgumentException("Unsupported catalogue sort");
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static boolean containsNul(String value) {
        return value != null && value.indexOf('\0') >= 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> scalars(Query query) {
        return query.getResultList();
    }
}
