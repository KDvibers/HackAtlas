package com.hackatlas.admin;

import java.util.UUID;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AdminAccessService {
    private final JdbcClient jdbc;

    public AdminAccessService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean allowed(UUID userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM hackatlas.admin_access WHERE user_id = :user)")
                .param("user", userId).query(Boolean.class).single();
    }

    /** Called inside the editing transaction, preventing revocation/deletion racing a write. */
    public void requireForMutation(UUID userId) {
        // Account deletion takes the account lock before cascading into admin_access.
        // Keep that same order instead of letting a join choose the row-lock order.
        boolean accountExists = jdbc.sql("SELECT id FROM hackatlas.user_account WHERE id=:user FOR SHARE")
                .param("user",userId).query(UUID.class).optional().isPresent();
        if (!accountExists || jdbc.sql("SELECT user_id FROM hackatlas.admin_access WHERE user_id=:user FOR SHARE")
                .param("user",userId).query(UUID.class).optional().isEmpty()) {
            throw new ApiFailure(403, "FORBIDDEN", "Административный доступ закрыт.");
        }
    }
}
