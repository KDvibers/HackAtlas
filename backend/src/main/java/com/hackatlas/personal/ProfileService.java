package com.hackatlas.personal;

import java.sql.Types;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ProfileService {
    private static final Map<String, String> COLUMNS = Map.of(
            "displayName", "display_name", "city", "city", "countryCode", "country_code",
            "roleLabel", "role_label", "avatarKey", "avatar_key");
    private final JdbcClient jdbc;
    private final Clock clock;

    public ProfileService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public PersonalDtos.Profile get(UUID userId) {
        List<String> interests = jdbc.sql("SELECT tag_id FROM hackatlas.user_interest WHERE user_id = :user ORDER BY tag_id")
                .param("user", userId).query(String.class).list();
        return jdbc.sql("""
                SELECT u.*,
                  (SELECT count(*) FROM hackatlas.saved_hackathon s
                   JOIN hackatlas.catalog_hackathon h ON h.id = s.hackathon_id
                   WHERE s.user_id = u.id AND h.publication_status = 'PUBLISHED') saved_count,
                  (SELECT count(*) FROM hackatlas.application_mark a
                   JOIN hackatlas.catalog_hackathon h ON h.id = a.hackathon_id
                   WHERE a.user_id = u.id AND h.publication_status = 'PUBLISHED') applied_count
                FROM hackatlas.user_account u WHERE u.id = :user
                """).param("user", userId).query((rs, row) -> new PersonalDtos.Profile(
                        userId, rs.getString("email"), rs.getString("display_name"), rs.getString("city"),
                        rs.getString("country_code"), rs.getString("role_label"), rs.getString("avatar_key"), interests,
                        new PersonalDtos.Stats(rs.getLong("saved_count"), rs.getLong("applied_count"), interests.size())))
                .optional().orElseThrow(() -> new ApiFailure(401, "AUTH_REQUIRED", "Требуется вход в аккаунт."));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PersonalDtos.Profile patch(UUID userId, ProfilePatch patch) {
        // Serialize patches for this account so two concurrent partial updates cannot lose fields/interests.
        jdbc.sql("SELECT id FROM hackatlas.user_account WHERE id = :user FOR UPDATE")
                .param("user", userId).query(UUID.class).optional()
                .orElseThrow(() -> new ApiFailure(401, "AUTH_REQUIRED", "Требуется вход в аккаунт."));
        if (patch.interests() != null) {
            List<String> allowed = jdbc.sql("SELECT id FROM hackatlas.catalog_tag WHERE interest_allowed = true")
                    .query(String.class).list();
            if (!allowed.containsAll(patch.interests())) {
                throw new ApiFailure(400, "VALIDATION_ERROR", "Выберите интересы из доступного списка.");
            }
        }
        for (var entry : patch.fields().entrySet()) {
            String column = COLUMNS.get(entry.getKey());
            if (column == null) {
                throw new IllegalArgumentException("Unknown profile field");
            }
            jdbc.sql("UPDATE hackatlas.user_account SET " + column + " = :value WHERE id = :user")
                    .param("value", entry.getValue(), Types.VARCHAR).param("user", userId).update();
        }
        if (patch.interests() != null) {
            jdbc.sql("DELETE FROM hackatlas.user_interest WHERE user_id = :user").param("user", userId).update();
            for (String tag : patch.interests()) {
                jdbc.sql("INSERT INTO hackatlas.user_interest (user_id, tag_id) VALUES (:user, :tag)")
                        .param("user", userId).param("tag", tag).update();
            }
        }
        jdbc.sql("UPDATE hackatlas.user_account SET updated_at = :now WHERE id = :user")
                .param("now", java.sql.Timestamp.from(clock.instant())).param("user", userId).update();
        return get(userId);
    }
}
