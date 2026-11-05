package com.hackatlas.auth.lifecycle;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.hackatlas.auth.AuthService;
import com.hackatlas.auth.SessionPrincipal;
import com.hackatlas.web.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "hackatlas.lifecycle.enabled", havingValue = "true")
public class LifecycleService {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleService.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final EmailActionStore store;
    private final EmailActionCrypto crypto;
    private final LifecycleMailQueue queue;
    private final LifecycleMailer mailer;
    private final LifecycleRateLimiter limiter;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    public LifecycleService(JdbcTemplate jdbc, Clock clock, EmailActionStore store, EmailActionCrypto crypto,
            LifecycleMailQueue queue, LifecycleMailer mailer, LifecycleRateLimiter limiter,
            PasswordEncoder passwords, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.jdbc = jdbc; this.clock = clock; this.store = store; this.crypto = crypto;
        this.queue = queue; this.mailer = mailer; this.limiter = limiter; this.passwords = passwords;
        this.transactions = new TransactionTemplate(manager); this.metrics = metrics;
    }

    public Optional<EmailActionCrypto.Challenge> requestVerification(SessionPrincipal principal, String ip) {
        var account = tx(() -> principalAccount(principal));
        if (account.verified()) return Optional.empty();
        limiter.request(ip, account.email());
        var challenge = crypto.newChallenge(now());
        queue.submit(() -> deliver(account.id(), account.version(), null, EmailActionPurpose.EMAIL_VERIFICATION, challenge));
        count("scheduled");
        return Optional.of(challenge);
    }

    public EmailActionCrypto.Challenge requestReset(String email, String ip) {
        String normalized = AuthService.normalizeEmail(email);
        limiter.request(ip, normalized);
        var challenge = crypto.newChallenge(now());
        // No account lookup on this request thread. Unknown/deleted emails reserve the same work.
        queue.submit(() -> deliver(null, null, normalized, EmailActionPurpose.PASSWORD_RESET, challenge));
        count("scheduled");
        return challenge;
    }

    public void confirmVerification(SessionPrincipal principal, String challengeId, String code, String ip) {
        limiter.verify(ip);
        boolean accepted = tx(() -> {
            principalAccount(principal);
            var proof = store.consumeVerification(principal.userId(), challengeId, code);
            if (proof.isEmpty()) return false;
            jdbc.update("UPDATE hackatlas.user_account SET email_verified=TRUE,updated_at=? WHERE id=?",
                    timestamp(now()), proof.get().userId());
            jdbc.update("DELETE FROM hackatlas.email_action_challenge WHERE user_id=? AND purpose='EMAIL_VERIFICATION'", proof.get().userId());
            return true;
        });
        if (!accepted) throw invalid(); // The wrong-attempt transaction has already committed.
    }

    public EmailActionCrypto.ResetGrant verifyReset(String challengeId, String code, String ip) {
        limiter.verify(ip);
        return tx(() -> store.exchangeResetCode(challengeId, code)).orElseThrow(LifecycleService::invalid);
    }

    public void confirmReset(String token, String password, String ip) {
        limiter.verify(ip);
        String hash = passwords.encode(password);
        var changed = tx(() -> {
            var proof = store.consumeResetGrant(token);
            if (proof.isEmpty()) return Optional.<Changed>empty();
            UUID user = proof.get().userId();
            Instant now = now();
            jdbc.update("""
                    UPDATE hackatlas.user_account SET password_hash=?,credential_version=credential_version+1,
                    email_verified=TRUE,password_reset_required=FALSE,credential_changed_at=?,updated_at=? WHERE id=?
                    """, hash, timestamp(now), timestamp(now), user);
            jdbc.update("UPDATE hackatlas.auth_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", timestamp(now), user);
            jdbc.update("DELETE FROM hackatlas.email_action_challenge WHERE user_id=?", user);
            return Optional.of(new Changed(user, proof.get().credentialVersion() + 1));
        }).orElseThrow(LifecycleService::invalid);
        // A full/broken notification queue must not roll back a committed password change or return an error.
        try { queue.submit(() -> notifyChanged(changed)); }
        catch (ApiFailure failure) { count("notification_dropped"); }
    }

    private void deliver(UUID user, Long expectedVersion, String email, EmailActionPurpose purpose,
                         EmailActionCrypto.Challenge challenge) {
        try {
            var delivery = tx(() -> {
                var row = account(user, email);
                if (row.isEmpty() || !eligible(row.get(), expectedVersion, purpose, challenge)) return Optional.<Delivery>empty();
                var value = row.get();
                if (!store.createChallenge(value.id(), purpose, challenge)) return Optional.<Delivery>empty();
                return Optional.of(new Delivery(value.id(), value.version(), value.address()));
            });
            if (delivery.isEmpty()) { count("skipped"); return; }
            boolean sent = tx(() -> {
                var row = account(delivery.get().user(), null);
                if (row.isEmpty() || !eligible(row.get(), delivery.get().version(), purpose, challenge)
                        || !delivery.get().address().equals(row.get().address())) return false;
                int usable = jdbc.queryForObject("""
                        SELECT count(*) FROM hackatlas.email_action_challenge WHERE id_hash=? AND user_id=?
                        AND credential_version=? AND purpose=? AND consumed_at IS NULL AND failed_attempts<5 AND expires_at>?
                        """, Integer.class, crypto.hashToken(challenge.challengeId()), row.get().id(), row.get().version(), purpose.name(), timestamp(now()));
                if (usable != 1) return false;
                mailer.code(row.get().address(), purpose, challenge);
                return true;
            });
            count(sent ? "sent" : "skipped");
        } catch (RuntimeException exception) {
            count("failed");
            LOG.warn("Lifecycle delivery failed"); // No SMTP/SQL messages, addresses, codes or stack traces.
        }
    }

    private void notifyChanged(Changed changed) {
        try {
            boolean sent = tx(() -> {
                var row = account(changed.user(), null);
                if (row.isEmpty() || row.get().version() != changed.version() || row.get().address() == null) return false;
                mailer.passwordChanged(row.get().address());
                return true;
            });
            count(sent ? "notification_sent" : "notification_dropped");
        } catch (RuntimeException exception) { count("notification_dropped"); LOG.warn("Lifecycle notification failed"); }
    }

    private boolean eligible(Account account, Long version, EmailActionPurpose purpose, EmailActionCrypto.Challenge challenge) {
        return account.address() != null && (version == null || version == account.version())
                && (account.changed() == null || challenge.createdAt().isAfter(account.changed()))
                && (purpose != EmailActionPurpose.EMAIL_VERIFICATION || !account.verified())
                && !now().isBefore(challenge.createdAt()) && now().isBefore(challenge.expiresAt());
    }

    private Account principalAccount(SessionPrincipal principal) {
        var account = account(principal.userId(), null).orElseThrow(LifecycleService::unauthorized);
        int sessions = jdbc.queryForObject("""
                SELECT count(*) FROM hackatlas.auth_session WHERE id=? AND user_id=? AND credential_version=?
                AND revoked_at IS NULL AND expires_at>?
                """, Integer.class, principal.sessionId(), principal.userId(), account.version(), timestamp(now()));
        if (sessions != 1 || account.resetRequired() || principal.credentialVersion() != account.version()) throw unauthorized();
        return account;
    }

    private Optional<Account> account(UUID user, String email) {
        String predicate = user == null ? "email=?" : "id=?";
        return jdbc.query("SELECT id,email,email_delivery_address,credential_version,email_verified,password_reset_required,credential_changed_at"
                + " FROM hackatlas.user_account WHERE " + predicate + " FOR UPDATE", (rs, row) -> new Account(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5), rs.getBoolean(6),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()), user == null ? email : user).stream().findFirst();
    }

    private <T> T tx(Supplier<T> callback) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Lifecycle facade owns its transaction boundary");
        return transactions.execute(status -> callback.get());
    }
    private void count(String outcome) { metrics.counter("hackatlas.lifecycle.delivery", "outcome", outcome).increment(); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Timestamp timestamp(Instant time) { return Timestamp.from(time); }
    private static ApiFailure invalid() { return new ApiFailure(400, "EMAIL_ACTION_INVALID", "Код или действие недействительны. Запросите новый код."); }
    private static ApiFailure unauthorized() { return new ApiFailure(401, "AUTH_REQUIRED", "Требуется действующая сессия."); }
    private record Changed(UUID user, long version) { }
    private record Account(UUID id, String email, String address, long version, boolean verified, boolean resetRequired, Instant changed) {
        @Override public String toString() { return "LifecycleAccount[REDACTED]"; }
    }
    private record Delivery(UUID user, long version, String address) {
        @Override public String toString() { return "LifecycleDelivery[REDACTED]"; }
    }
}
