package com.hackatlas.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.hackatlas.web.ApiFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PasswordEncoder passwords;
    private final AuthRateLimiter limiter;
    private final boolean registrationEnabled;
    private final Duration sessionTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthService(
            JdbcTemplate jdbc, Clock clock, PasswordEncoder passwords, AuthRateLimiter limiter,
            Environment environment,
            @Value("${hackatlas.auth.registration-enabled:false}") boolean registrationEnabled,
            @Value("${hackatlas.auth.session-ttl:P30D}") Duration sessionTtl
    ) {
        if (sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("Session TTL must be positive");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.passwords = passwords;
        this.limiter = limiter;
        this.registrationEnabled = registrationEnabled
                && environment.acceptsProfiles(Profiles.of("(dev | test) & !prod"));
        this.sessionTtl = sessionTtl;
        // Missing accounts still perform the same expensive verification operation.
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public IssuedSession register(String email, String password, String displayName) {
        if (!registrationEnabled) {
            throw new ApiFailure(403, "FORBIDDEN", "Регистрация сейчас недоступна.");
        }
        String normalizedEmail = normalizeEmail(email);
        limiter.checkAccount(normalizedEmail);
        String passwordHash = passwords.encode(password);
        UUID userId = UUID.randomUUID();
        Instant now = now();
        int inserted = jdbc.update("""
                INSERT INTO hackatlas.user_account
                    (id, email, password_hash, display_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (email) DO NOTHING
                """, userId, normalizedEmail, passwordHash, displayName, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            throw new ApiFailure(409, "EMAIL_ALREADY_EXISTS", "Не удалось создать аккаунт с указанными данными.");
        }
        return issue(userId, now);
    }

    @Transactional
    public IssuedSession login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        limiter.checkAccount(normalizedEmail);
        List<AccountCredential> users = jdbc.query("""
                SELECT id, password_hash FROM hackatlas.user_account WHERE email = ? FOR UPDATE
                """, (rs, row) -> new AccountCredential(rs.getObject("id", UUID.class),
                rs.getString("password_hash")), normalizedEmail);
        AccountCredential user = users.isEmpty() ? null : users.getFirst();
        boolean matches = passwords.matches(password, user == null ? dummyPasswordHash : user.passwordHash);
        if (user == null || !matches) {
            throw new ApiFailure(401, "INVALID_CREDENTIALS",
                    "Не удалось выполнить вход. Проверьте данные и повторите попытку.");
        }
        return issue(user.userId, now());
    }

    @Transactional(readOnly = true)
    public Optional<SessionPrincipal> authenticate(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT id, user_id FROM hackatlas.auth_session
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
                """, (rs, row) -> new SessionPrincipal(rs.getObject("user_id", UUID.class),
                rs.getObject("id", UUID.class)), TokenHash.sha256(token), Timestamp.from(now()))
                .stream().findFirst();
    }

    @Transactional
    public void logout(SessionPrincipal principal) {
        Instant now = now();
        int revoked = jdbc.update("""
                UPDATE hackatlas.auth_session SET revoked_at = ?
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL AND expires_at > ?
                """, Timestamp.from(now), principal.sessionId(), principal.userId(), Timestamp.from(now));
        if (revoked == 0) {
            throw new ApiFailure(401, "AUTH_REQUIRED", "Требуется действующая сессия.");
        }
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private IssuedSession issue(UUID userId, Instant now) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant expiresAt = now.plus(sessionTtl).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO hackatlas.auth_session (id, user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), userId, TokenHash.sha256(token), Timestamp.from(now), Timestamp.from(expiresAt));
        return new IssuedSession(token, expiresAt, userId);
    }

    private Instant now() {
        // PostgreSQL stores microseconds; the response and persisted absolute expiry must agree.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static final class AccountCredential {
        private final UUID userId;
        private final String passwordHash;

        private AccountCredential(UUID userId, String passwordHash) {
            this.userId = userId;
            this.passwordHash = passwordHash;
        }
    }
}
