package com.hackatlas.admin;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hackatlas.moderation.ModerationService;
import com.hackatlas.moderation.SafeHttpsUrl;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdminCatalogueService {
    private static final Map<String, String> COLUMNS = Map.ofEntries(
            Map.entry("title", "title"), Map.entry("shortDescription", "short_description"),
            Map.entry("description", "description"), Map.entry("organizerName", "organizer_name"),
            Map.entry("organizerWebsiteUrl", "organizer_website_url"), Map.entry("format", "format"),
            Map.entry("countryCode", "country_code"), Map.entry("city", "city"), Map.entry("venue", "venue"),
            Map.entry("officialUrl", "official_url"), Map.entry("registrationUrl", "registration_url"),
            Map.entry("rulesUrl", "rules_url"), Map.entry("sourceUrl", "source_url"),
            Map.entry("registrationStatus", "registration_status"), Map.entry("eventStatus", "event_status"));
    private static final java.util.Set<String> NULLABLE = java.util.Set.of(
            "organizerWebsiteUrl", "countryCode", "city", "venue", "registrationUrl", "rulesUrl");
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AdminAccessService access;
    private final AdminAuditService audit;
    private final ModerationService moderation;

    public record EventRow(UUID id, String title, String publicationStatus, String eventStatus,
                           Instant updatedAt, Instant lastVerifiedAt, boolean demo) { }
    public record EventEditor(EventRow event, Map<String, String> form) { }

    public AdminCatalogueService(JdbcClient jdbc, Clock clock, AdminAccessService access,
                                 AdminAuditService audit, ModerationService moderation) {
        this.jdbc = jdbc; this.clock = clock; this.access = access; this.audit = audit; this.moderation = moderation;
    }

    @Transactional(readOnly = true)
    public List<EventRow> list(int page) {
        return jdbc.sql("""
                SELECT id,title,publication_status,event_status,updated_at,last_verified_at,is_demo
                FROM hackatlas.catalog_hackathon ORDER BY updated_at DESC,id ASC LIMIT 50 OFFSET :offset
                """).param("offset", (long) page * 50).query((rs, row) -> new EventRow(rs.getObject(1, UUID.class),
                rs.getString(2),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),rs.getBoolean(7))).list();
    }

    @Transactional(readOnly = true)
    public EventEditor editor(UUID id) {
        return jdbc.sql("SELECT * FROM hackatlas.catalog_hackathon WHERE id=:id").param("id", id).query((rs, row) -> {
            var data = AdminEventForm.defaults();
            for (var field : COLUMNS.entrySet()) {
                String value = rs.getString(field.getValue());
                data.put(field.getKey(), value == null ? "" : value);
            }
            for (String prefix : List.of("starts", "ends", "deadline")) {
                data.put(prefix + "Precision", rs.getString(prefix + "_precision") == null ? "" : rs.getString(prefix + "_precision"));
                data.put(prefix + "Date", rs.getDate(prefix + "_date") == null ? "" : rs.getDate(prefix + "_date").toLocalDate().toString());
                data.put(prefix + "Time", rs.getTime(prefix + "_time") == null ? "" : rs.getTime(prefix + "_time").toLocalTime().toString());
                data.put(prefix + "TimeZone", rs.getString(prefix + "_time_zone") == null ? "" : rs.getString(prefix + "_time_zone"));
            }
            return new EventEditor(new EventRow(id,rs.getString("title"),rs.getString("publication_status"),
                    rs.getString("event_status"),rs.getTimestamp("updated_at").toInstant(),
                    rs.getTimestamp("last_verified_at") == null ? null : rs.getTimestamp("last_verified_at").toInstant(),
                    rs.getBoolean("is_demo")),data);
        }).optional().orElseThrow(AdminCatalogueService::notFound);
    }

    public UUID create(UUID actor, AdminEventForm form) {
        access.requireForMutation(actor);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hackatlas.catalog_hackathon
                (id,slug,title,short_description,description,organizer_name,format,registration_status,event_status,
                 prize_status,prize_kind,artwork_key,updated_at,official_url,source_url)
                VALUES (:id,:slug,'Draft','','','Draft','ONLINE','UNKNOWN','SCHEDULED',
                        'UNKNOWN','UNSPECIFIED','WIREFRAME',:now,'','')
                """).param("id", id).param("slug", "event-" + id).param("now", Timestamp.from(clock.instant())).update();
        updateFields(id, form);
        audit.record(actor, "EVENT_CREATED", id);
        return id;
    }

    public void edit(UUID actor, UUID id, AdminEventForm form) {
        access.requireForMutation(actor);
        lock(id);
        var current = editor(id);
        if (current.event.publicationStatus.equals("PUBLISHED")) { requireSource(form.value("officialUrl"),form.value("sourceUrl")); }
        if (!current.form.get("sourceUrl").equals(form.value("sourceUrl"))
                || !current.form.get("officialUrl").equals(form.value("officialUrl"))) {
            // A previous source check cannot vouch for a different source or official site.
            jdbc.sql("UPDATE hackatlas.catalog_hackathon SET last_verified_at=NULL WHERE id=:id").param("id",id).update();
        }
        updateFields(id, form);
        audit.record(actor, "EVENT_EDITED", id);
    }

    public void publish(UUID actor, UUID id, boolean reviewConfirmed) {
        access.requireForMutation(actor);
        lock(id);
        var current = editor(id);
        if (!reviewConfirmed) { throw invalid("Подтвердите, что карточка проверена перед публикацией."); }
        var validation = new org.springframework.util.LinkedMultiValueMap<String,String>();
        current.form.forEach(validation::add);
        AdminEventForm.parse(validation);
        requireSource(current.form.get("officialUrl"), current.form.get("sourceUrl"));
        if (current.event.lastVerifiedAt == null) { throw invalid("Сначала явно отметьте проверку источника."); }
        jdbc.sql("""
                UPDATE hackatlas.catalog_hackathon SET publication_status='PUBLISHED',
                  published_at=COALESCE(published_at,:now),updated_at=:now WHERE id=:id
                """).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        audit.record(actor, "EVENT_PUBLISHED", id);
    }

    public void archive(UUID actor, UUID id) {
        access.requireForMutation(actor);
        lock(id);
        jdbc.sql("UPDATE hackatlas.catalog_hackathon SET publication_status='ARCHIVED',updated_at=:now WHERE id=:id")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        audit.record(actor, "EVENT_ARCHIVED", id);
    }

    public void verify(UUID actor, UUID id, boolean sourceChecked) {
        access.requireForMutation(actor);
        lock(id);
        var current = editor(id);
        if (!sourceChecked) { throw invalid("Подтвердите ручную проверку официального источника."); }
        requireSource(current.form.get("officialUrl"), current.form.get("sourceUrl"));
        jdbc.sql("UPDATE hackatlas.catalog_hackathon SET last_verified_at=:now WHERE id=:id")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        audit.record(actor, "SOURCE_VERIFIED", id);
    }

    public void review(UUID actor, UUID id, String decision) {
        access.requireForMutation(actor);
        moderation.review(id, decision);
        audit.record(actor, "SUBMISSION_" + decision, null);
    }

    private void updateFields(UUID id, AdminEventForm form) {
        var sql = new StringBuilder("UPDATE hackatlas.catalog_hackathon SET ");
        var values = new LinkedHashMap<String, Object>();
        for (var field : COLUMNS.entrySet()) {
            sql.append(field.getValue()).append("=:").append(field.getKey()).append(',');
            values.put(field.getKey(), NULLABLE.contains(field.getKey()) ? form.nullable(field.getKey()) : form.value(field.getKey()));
        }
        for (var entry : Map.of("starts", java.util.Optional.ofNullable(form.starts()),
                "ends", java.util.Optional.ofNullable(form.ends()), "deadline", java.util.Optional.ofNullable(form.deadline())).entrySet()) {
            String prefix = entry.getKey();
            var time = entry.getValue().orElse(null);
            for (String suffix : List.of("precision", "date", "time", "time_zone", "instant")) {
                String column = prefix + "_" + suffix;
                sql.append(column).append("=:").append(column).append(',');
                values.put(column, time == null ? null : switch (suffix) {
                    case "precision" -> time.precision();
                    case "date" -> java.sql.Date.valueOf(time.date());
                    case "time" -> time.time() == null ? null : java.sql.Time.valueOf(time.time());
                    case "time_zone" -> time.timeZone();
                    default -> time.instant() == null ? null : Timestamp.from(time.instant());
                });
            }
        }
        sql.append("updated_at=:updated WHERE id=:id");
        var query = jdbc.sql(sql.toString()).param("updated", Timestamp.from(clock.instant())).param("id", id);
        for (var value : values.entrySet()) {
            int type = value.getKey().endsWith("_date") ? Types.DATE : value.getKey().endsWith("_time") ? Types.TIME
                    : value.getKey().endsWith("_instant") ? Types.TIMESTAMP : Types.VARCHAR;
            query = query.param(value.getKey(), value.getValue(), type);
        }
        query.update();
    }

    private void lock(UUID id) {
        if (jdbc.sql("SELECT id FROM hackatlas.catalog_hackathon WHERE id=:id FOR UPDATE")
                .param("id", id).query(UUID.class).optional().isEmpty()) { throw notFound(); }
    }

    private static void requireSource(String official, String source) {
        if (official.isBlank() || source.isBlank()) { throw invalid("Нужны официальный сайт и проверяемый источник."); }
        SafeHttpsUrl.validate(official,"officialUrl");
        SafeHttpsUrl.validate(source,"sourceUrl");
    }

    private static ApiFailure notFound() { return new ApiFailure(404,"NOT_FOUND","Запись не найдена."); }
    private static ApiFailure invalid(String message) { return new ApiFailure(400,"VALIDATION_ERROR",message); }
}
