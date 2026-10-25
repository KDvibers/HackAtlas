package com.hackatlas.personal;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PersonalListService {
    private final JdbcClient jdbc;
    private final Clock clock;

    public PersonalListService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void save(UUID userId, UUID id) {
        requirePublished(id);
        jdbc.sql("""
                INSERT INTO hackatlas.saved_hackathon (user_id, hackathon_id, saved_at)
                VALUES (:user, :id, :now) ON CONFLICT (user_id, hackathon_id) DO NOTHING
                """).param("user", userId).param("id", id).param("now", Timestamp.from(clock.instant())).update();
    }

    public void unsave(UUID userId, UUID id) {
        jdbc.sql("DELETE FROM hackatlas.saved_hackathon WHERE user_id = :user AND hackathon_id = :id")
                .param("user", userId).param("id", id).update();
    }

    public PersonalDtos.ApplicationMark apply(UUID userId, UUID id) {
        requirePublished(id);
        // PostgreSQL resolves competing inserts; the initial timestamp survives retries.
        return jdbc.sql("""
                INSERT INTO hackatlas.application_mark (user_id, hackathon_id, marked_at)
                VALUES (:user, :id, :now) ON CONFLICT (user_id, hackathon_id)
                DO UPDATE SET marked_at = hackatlas.application_mark.marked_at RETURNING marked_at
                """).param("user", userId).param("id", id).param("now", Timestamp.from(clock.instant()))
                .query((rs, row) -> new PersonalDtos.ApplicationMark(id, "APPLIED", true, rs.getTimestamp(1).toInstant())).single();
    }

    public void unapply(UUID userId, UUID id) {
        jdbc.sql("DELETE FROM hackatlas.application_mark WHERE user_id = :user AND hackathon_id = :id")
                .param("user", userId).param("id", id).update();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PersonalDtos.ApplicationPage applications(UUID userId, BigInteger page, int size) {
        var asOf = clock.instant();
        String from = """
                 FROM hackatlas.application_mark a JOIN hackatlas.catalog_hackathon h ON h.id = a.hackathon_id
                 WHERE a.user_id = :user AND h.publication_status = 'PUBLISHED'
                """;
        long count = jdbc.sql("SELECT count(*)" + from).param("user", userId).query(Long.class).single();
        long pages = count / size + (count % size == 0 ? 0 : 1);
        BigInteger offset = page.multiply(BigInteger.valueOf(size));
        if (offset.compareTo(BigInteger.valueOf(count)) >= 0) {
            return new PersonalDtos.ApplicationPage(List.of(), page, size, count, pages, false, asOf);
        }
        var items = jdbc.sql("SELECT a.hackathon_id, a.marked_at" + from
                        + " ORDER BY a.marked_at DESC, a.hackathon_id ASC LIMIT :size OFFSET :offset")
                .param("user", userId).param("size", size).param("offset", offset.longValueExact())
                .query((rs, row) -> new PersonalDtos.ApplicationMark(rs.getObject(1, UUID.class), "APPLIED", true,
                        rs.getTimestamp(2).toInstant())).list();
        return new PersonalDtos.ApplicationPage(items, page, size, count, pages,
                offset.add(BigInteger.valueOf(items.size())).compareTo(BigInteger.valueOf(count)) < 0, asOf);
    }

    private void requirePublished(UUID id) {
        // Lock also protects this decision against concurrent archival/deletion.
        if (jdbc.sql("SELECT id FROM hackatlas.catalog_hackathon WHERE id = :id AND publication_status = 'PUBLISHED' FOR SHARE")
                .param("id", id).query(UUID.class).optional().isEmpty()) {
            throw new ApiFailure(404, "NOT_FOUND", "Событие не найдено или недоступно.");
        }
    }
}
