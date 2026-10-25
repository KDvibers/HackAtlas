package com.hackatlas.moderation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ModerationService {
    private final JdbcClient jdbc;
    private final Clock clock;

    public ModerationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Receipt(UUID id, String status) { }
    public record Submission(UUID id, UUID hackathonId, String kind, String url, String comment,
            String reason, String message, Instant createdAt) { }

    public Receipt suggest(UUID userId, String url, String comment) {
        requireAccount(userId);
        SafeHttpsUrl.validate(url, "url");
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hackatlas.moderation_submission (id, user_id, kind, url, comment, created_at)
                VALUES (:id, :user, 'SUGGESTION', :url, :comment, :now)
                """).param("id", id).param("user", userId).param("url", url)
                .param("comment", comment, java.sql.Types.VARCHAR).param("now", Timestamp.from(clock.instant())).update();
        return new Receipt(id, "PENDING");
    }

    public Receipt report(UUID userId, UUID hackathonId, String reason, String message) {
        requireAccount(userId);
        if (jdbc.sql("SELECT id FROM hackatlas.catalog_hackathon WHERE id = :id AND publication_status = 'PUBLISHED' FOR SHARE")
                .param("id", hackathonId).query(UUID.class).optional().isEmpty()) {
            throw new ApiFailure(404, "NOT_FOUND", "Событие не найдено или недоступно.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hackatlas.moderation_submission (id, user_id, hackathon_id, kind, reason, message, created_at)
                VALUES (:id, :user, :event, 'REPORT', :reason, :message, :now)
                """).param("id", id).param("user", userId).param("event", hackathonId)
                .param("reason", reason).param("message", message).param("now", Timestamp.from(clock.instant())).update();
        return new Receipt(id, "PENDING");
    }

    @Transactional(readOnly = true)
    public List<Submission> pending() {
        return jdbc.sql("""
                SELECT id, hackathon_id, kind, url, comment, reason, message, created_at
                FROM hackatlas.moderation_submission WHERE status = 'PENDING' ORDER BY created_at, id LIMIT 100
                """).query((rs, row) -> new Submission(rs.getObject("id", UUID.class), rs.getObject("hackathon_id", UUID.class),
                        rs.getString("kind"), rs.getString("url"), rs.getString("comment"), rs.getString("reason"),
                        rs.getString("message"), rs.getTimestamp("created_at").toInstant())).list();
    }

    public void review(UUID id, String decision) {
        if (!Set.of("RESOLVED", "DISMISSED").contains(decision)) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Выберите результат проверки.");
        }
        int count = jdbc.sql("""
                UPDATE hackatlas.moderation_submission SET status = :status, reviewed_at = :now
                WHERE id = :id AND status = 'PENDING'
                """).param("status", decision).param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (count == 0) { throw new ApiFailure(404, "NOT_FOUND", "Обращение не найдено в очереди."); }
    }

    private void requireAccount(UUID userId) {
        if (jdbc.sql("SELECT id FROM hackatlas.user_account WHERE id = :id FOR KEY SHARE")
                .param("id", userId).query(UUID.class).optional().isEmpty()) {
            throw new ApiFailure(401, "AUTH_REQUIRED", "Требуется действующая сессия.");
        }
    }
}
