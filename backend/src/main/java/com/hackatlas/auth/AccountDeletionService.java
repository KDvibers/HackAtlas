package com.hackatlas.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import com.hackatlas.web.ApiFailure;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final AuthRateLimiter limiter;
    private final Clock clock;

    public AccountDeletionService(JdbcClient jdbc, PasswordEncoder passwords, AuthRateLimiter limiter, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Transactional
    public void delete(SessionPrincipal principal, String password) {
        // Same account lock as login: no new session can survive a concurrent successful deletion.
        var account = jdbc.sql("SELECT email, password_hash FROM hackatlas.user_account WHERE id = :user FOR UPDATE")
                .param("user", principal.userId()).query((rs, row) -> new Credentials(rs.getString(1), rs.getString(2)))
                .optional().orElseThrow(() -> new ApiFailure(401, "AUTH_REQUIRED", "Требуется действующая сессия."));
        limiter.checkAccount(account.email);
        if (!passwords.matches(password, account.hash)) {
            throw new ApiFailure(401, "INVALID_CREDENTIALS", "Не удалось подтвердить пароль.");
        }
        // Cascading FKs erase all tokens, profile links and submitted free text in the same transaction.
        jdbc.sql("DELETE FROM hackatlas.user_account WHERE id = :user").param("user", principal.userId()).update();
        jdbc.sql("INSERT INTO hackatlas.admin_audit (id, action, created_at) VALUES (:id, 'ACCOUNT_DELETED', :now)")
                .param("id", UUID.randomUUID()).param("now", Timestamp.from(clock.instant())).update();
    }

    private static final class Credentials {
        private final String email;
        private final String hash;
        private Credentials(String email, String hash) { this.email = email; this.hash = hash; }
    }
}
