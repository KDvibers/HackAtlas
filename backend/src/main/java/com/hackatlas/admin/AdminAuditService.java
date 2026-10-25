package com.hackatlas.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private final JdbcClient jdbc;
    private final Clock clock;

    public AdminAuditService(JdbcClient jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public void record(UUID actorId, String action, UUID eventId) {
        jdbc.sql("""
                INSERT INTO hackatlas.admin_audit (id, actor_id, action, event_id, created_at)
                VALUES (:id, :actor, :action, :event, :now)
                """).param("id", UUID.randomUUID()).param("actor", actorId).param("action", action)
                .param("event", eventId, java.sql.Types.OTHER).param("now", Timestamp.from(clock.instant())).update();
    }
}
