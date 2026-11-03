package com.hackatlas.auth.lifecycle;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal transaction-bound storage; deliberately not registered as a runtime bean. */
public final class EmailActionStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final EmailActionCrypto crypto;

    public EmailActionStore(JdbcTemplate jdbc, Clock clock, EmailActionCrypto crypto) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.crypto = Objects.requireNonNull(crypto);
    }

    public boolean createChallenge(UUID user, EmailActionPurpose purpose, EmailActionCrypto.Challenge challenge) {
        requireTransaction();
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(challenge);
        var account = lockAccount(Objects.requireNonNull(user));
        if (account.isEmpty() || !account.get().hasDeliveryAddress()) return false;
        Instant now = now();
        if (now.isBefore(challenge.createdAt()) || !now.isBefore(challenge.expiresAt())) return false;
        long version = account.get().version();
        // Account lock serializes all issue/consume/delete operations; resend never cancels an old code.
        var recent = jdbc.queryForObject("""
                SELECT count(*) FROM hackatlas.email_action_challenge
                WHERE user_id=? AND purpose=? AND credential_version=? AND accepted_at>?
                """, Integer.class, user, purpose.name(), version, timestamp(now.minusSeconds(60)));
        if (recent > 0) return false;
        var active = jdbc.queryForObject("""
                SELECT count(*) FROM hackatlas.email_action_challenge
                WHERE user_id=? AND purpose=? AND credential_version=?
                    AND consumed_at IS NULL AND failed_attempts<5 AND expires_at>?
                """, Integer.class, user, purpose.name(), version, timestamp(now));
        if (active >= 3) return false;
        return jdbc.update("""
                INSERT INTO hackatlas.email_action_challenge
                    (id_hash,user_id,purpose,credential_version,code_mac,created_at,accepted_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id_hash) DO NOTHING
                """, crypto.hashToken(challenge.challengeId()), user, purpose.name(), version,
                crypto.codeMac(purpose, user, version, challenge.challengeId(), challenge.code()),
                timestamp(challenge.createdAt()), timestamp(now), timestamp(challenge.expiresAt())) == 1;
    }

    public Optional<VerifiedAction> consumeVerification(UUID user, String challengeId, String code) {
        requireTransaction();
        return consumeChallenge(EmailActionPurpose.EMAIL_VERIFICATION, Objects.requireNonNull(user), challengeId, code);
    }

    public Optional<EmailActionCrypto.ResetGrant> exchangeResetCode(String challengeId, String code) {
        requireTransaction();
        var proof = consumeChallenge(EmailActionPurpose.PASSWORD_RESET, null, challengeId, code);
        if (proof.isEmpty()) return Optional.empty();
        var grant = crypto.newResetGrant(now());
        jdbc.update("""
                INSERT INTO hackatlas.password_reset_grant
                    (token_hash,challenge_hash,user_id,credential_version,created_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, crypto.hashToken(grant.resetToken()), crypto.hashToken(challengeId),
                proof.get().userId(), proof.get().credentialVersion(), timestamp(grant.createdAt()), timestamp(grant.expiresAt()));
        return Optional.of(grant);
    }

    public Optional<VerifiedAction> consumeResetGrant(String token) {
        requireTransaction();
        if (!EmailActionCrypto.validToken(token)) return Optional.empty();
        String hash = crypto.hashToken(token);
        var user = jdbc.query("SELECT user_id FROM hackatlas.password_reset_grant WHERE token_hash=?",
                (rs, row) -> rs.getObject(1, UUID.class), hash).stream().findFirst();
        if (user.isEmpty()) return Optional.empty();
        var account = lockAccount(user.get());
        if (account.isEmpty()) return Optional.empty();
        var grants = jdbc.query("""
                SELECT credential_version,created_at,expires_at,consumed_at
                FROM hackatlas.password_reset_grant WHERE token_hash=? FOR UPDATE
                """, (rs, row) -> new GrantRow(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(), rs.getTimestamp(4)), hash);
        if (grants.isEmpty()) return Optional.empty();
        var grant = grants.getFirst();
        // Read time after waiting for locks, so waiting cannot extend a proof's lifetime.
        Instant now = now();
        if (grant.version() != account.get().version() || grant.consumed() != null
                || now.isBefore(grant.created()) || !now.isBefore(grant.expires())) return Optional.empty();
        jdbc.update("UPDATE hackatlas.password_reset_grant SET consumed_at=? WHERE token_hash=?", timestamp(now), hash);
        return Optional.of(new VerifiedAction(user.get(), grant.version()));
    }

    private Optional<VerifiedAction> consumeChallenge(EmailActionPurpose purpose, UUID expectedUser,
                                                       String challengeId, String code) {
        if (!EmailActionCrypto.validToken(challengeId) || !EmailActionCrypto.validCode(code)) return Optional.empty();
        String hash = crypto.hashToken(challengeId);
        var user = jdbc.query("SELECT user_id FROM hackatlas.email_action_challenge WHERE id_hash=?",
                (rs, row) -> rs.getObject(1, UUID.class), hash).stream().findFirst();
        if (user.isEmpty() || expectedUser != null && !expectedUser.equals(user.get())) return Optional.empty();
        var account = lockAccount(user.get());
        if (account.isEmpty()) return Optional.empty();
        var challenges = jdbc.query("""
                SELECT purpose,credential_version,code_mac,failed_attempts,created_at,expires_at,consumed_at
                FROM hackatlas.email_action_challenge WHERE id_hash=? FOR UPDATE
                """, (rs, row) -> new ChallengeRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getInt(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7)), hash);
        if (challenges.isEmpty()) return Optional.empty();
        var challenge = challenges.getFirst();
        Instant now = now();
        if (!purpose.name().equals(challenge.purpose()) || challenge.version() != account.get().version()
                || challenge.consumed() != null || challenge.attempts() >= 5
                || now.isBefore(challenge.created()) || !now.isBefore(challenge.expires())) return Optional.empty();
        if (!crypto.matchesCode(challenge.mac(), purpose, user.get(), challenge.version(), challengeId, code)) {
            jdbc.update("UPDATE hackatlas.email_action_challenge SET failed_attempts=failed_attempts+1 WHERE id_hash=?", hash);
            // Value result: the caller commits this counter before mapping an invalid code to HTTP 400.
            return Optional.empty();
        }
        jdbc.update("UPDATE hackatlas.email_action_challenge SET consumed_at=? WHERE id_hash=?", timestamp(now), hash);
        return Optional.of(new VerifiedAction(user.get(), challenge.version()));
    }

    private Optional<Account> lockAccount(UUID user) {
        return jdbc.query("""
                SELECT credential_version,email_delivery_address IS NOT NULL
                FROM hackatlas.user_account WHERE id=? FOR UPDATE
                """, (rs, row) -> new Account(rs.getLong(1), rs.getBoolean(2)), user).stream().findFirst();
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Email action storage requires a transaction on its JDBC data source");
        }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }

    private record Account(long version, boolean hasDeliveryAddress) { }
    private record ChallengeRow(String purpose, long version, String mac, int attempts,
                                Instant created, Instant expires, Timestamp consumed) { }
    private record GrantRow(long version, Instant created, Instant expires, Timestamp consumed) { }

    public record VerifiedAction(UUID userId, long credentialVersion) { }
}
