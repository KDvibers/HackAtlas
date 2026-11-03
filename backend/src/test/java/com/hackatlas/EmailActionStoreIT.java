package com.hackatlas;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.hackatlas.auth.AuthService;
import com.hackatlas.auth.lifecycle.EmailActionCrypto;
import com.hackatlas.auth.lifecycle.EmailActionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.hackatlas.auth.lifecycle.EmailActionPurpose.EMAIL_VERIFICATION;
import static com.hackatlas.auth.lifecycle.EmailActionPurpose.PASSWORD_RESET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
class EmailActionStoreIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuthService auth;
    private MutableClock clock;
    private EmailActionCrypto crypto;
    private EmailActionStore store;
    private UUID user;

    @BeforeEach
    void createOnlyDisposableAccounts() {
        jdbc.execute("TRUNCATE hackatlas.user_account CASCADE");
        clock = new MutableClock();
        crypto = new EmailActionCrypto(new byte[32]); // Synthetic test key, never a deployment default.
        store = new EmailActionStore(jdbc, clock, crypto);
        user = account("owner@example.com");
    }

    @Test
    void refusesEveryMutationWithoutACallerTransaction() {
        var challenge = crypto.newChallenge(clock.instant());
        assertThatIllegalStateException().isThrownBy(() -> store.createChallenge(user, EMAIL_VERIFICATION, challenge));
        assertThatIllegalStateException().isThrownBy(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code()));
        assertThatIllegalStateException().isThrownBy(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code()));
        assertThatIllegalStateException().isThrownBy(() -> store.consumeResetGrant(challenge.challengeId()));
        assertThat(count("email_action_challenge")).isZero();
    }

    @Test
    void anotherDataSourceTransactionCannotAccidentallyTurnStorageIntoAutocommit() {
        var unrelated = new DataSourceTransactionManager(new LazyConnectionDataSourceProxy(jdbc.getDataSource()));
        var candidate = crypto.newChallenge(clock.instant());
        assertThatIllegalStateException().isThrownBy(() -> new TransactionTemplate(unrelated)
                .execute(status -> store.createChallenge(user, EMAIL_VERIFICATION, candidate)));
        assertThat(count("email_action_challenge")).isZero();
    }

    @Test
    void storesOnlyBoundMacAndTokenHashAndDoesNotChangeAccountVerification() {
        var challenge = challenge(EMAIL_VERIFICATION);
        var row = jdbc.queryForMap("SELECT * FROM hackatlas.email_action_challenge");
        assertThat(row.get("id_hash")).isEqualTo(crypto.hashToken(challenge.challengeId()));
        assertThat(row.get("code_mac")).isEqualTo(crypto.codeMac(EMAIL_VERIFICATION, user, 0, challenge.challengeId(), challenge.code()));
        assertThat(row.values()).doesNotContain(challenge.challengeId(), challenge.code());
        var proof = tx(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code())).orElseThrow();
        assertThat(proof.userId()).isEqualTo(user);
        assertThat(proof.credentialVersion()).isZero();
        assertThat(jdbc.queryForObject("SELECT email_verified FROM hackatlas.user_account WHERE id=?", Boolean.class, user)).isFalse();
        assertThat(tx(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code()))).isEmpty();
    }

    @Test
    void commitsFiveWrongAttemptsAcrossStoreRecreationAndNeverAcceptsTheCorrectSixthAttempt() {
        var challenge = challenge(EMAIL_VERIFICATION);
        for (int attempt = 0; attempt < 5; attempt++) {
            store = new EmailActionStore(jdbc, clock, new EmailActionCrypto(new byte[32]));
            assertThat(tx(() -> store.consumeVerification(user, challenge.challengeId(), wrong(challenge)))).isEmpty();
        }
        assertThat(attempts(challenge)).isEqualTo(5);
        assertThat(tx(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code()))).isEmpty();
    }

    @Test
    void correctFifthAttemptStillWorksAndAnotherAccountOrPurposeCannotSpendIt() {
        var challenge = challenge(EMAIL_VERIFICATION);
        UUID other = account("other@example.com");
        assertThat(tx(() -> store.consumeVerification(other, challenge.challengeId(), challenge.code()))).isEmpty();
        assertThat(tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code()))).isEmpty();
        assertThat(attempts(challenge)).isZero();
        for (int attempt = 0; attempt < 4; attempt++) tx(() -> store.consumeVerification(user, challenge.challengeId(), wrong(challenge)));
        assertThat(tx(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code()))).isPresent();
    }

    @Test
    void versionChangeAndKeyRotationInvalidateOldCodes() {
        var challenge = challenge(EMAIL_VERIFICATION);
        var rotated = new byte[32];
        rotated[0] = 1;
        var afterRotation = new EmailActionStore(jdbc, clock, new EmailActionCrypto(rotated));
        assertThat(tx(() -> afterRotation.consumeVerification(user, challenge.challengeId(), challenge.code()))).isEmpty();
        assertThat(attempts(challenge)).isEqualTo(1);
        jdbc.update("UPDATE hackatlas.user_account SET credential_version=1 WHERE id=?", user);
        assertThat(tx(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code()))).isEmpty();
        assertThat(attempts(challenge)).isEqualTo(1);
    }

    @Test
    void cooldownHasAnExactBoundaryAndResendDoesNotCancelAnExistingCode() {
        var old = challenge(EMAIL_VERIFICATION);
        var reset = crypto.newChallenge(clock.instant());
        assertThat(tx(() -> store.createChallenge(user, PASSWORD_RESET, reset))).isTrue();
        clock.advance(Duration.ofSeconds(60).minusNanos(1000));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, crypto.newChallenge(clock.instant())))).isFalse();
        clock.advance(Duration.ofNanos(1000));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, crypto.newChallenge(clock.instant())))).isTrue();
        assertThat(tx(() -> store.consumeVerification(user, old.challengeId(), old.code()))).isPresent();
    }

    @Test
    void activeLimitKeepsOldCodesAndReleasesSpaceOnlyAfterConsumptionOrExpiry() {
        var old = challenge(EMAIL_VERIFICATION);
        for (int index = 0; index < 2; index++) {
            clock.advance(Duration.ofSeconds(60));
            challenge(EMAIL_VERIFICATION);
        }
        clock.advance(Duration.ofSeconds(60));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, crypto.newChallenge(clock.instant())))).isFalse();
        assertThat(count("email_action_challenge")).isEqualTo(3);
        assertThat(tx(() -> store.consumeVerification(user, old.challengeId(), old.code()))).isPresent();
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, crypto.newChallenge(clock.instant())))).isTrue();
        clock.advance(Duration.ofMinutes(10));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, crypto.newChallenge(clock.instant())))).isTrue();
    }

    @Test
    void delayedOrFutureDatedCandidateCannotBePersistedOrExtendItsAcknowledgedDeadline() {
        var delayed = crypto.newChallenge(clock.instant());
        clock.advance(Duration.ofMinutes(10));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, delayed))).isFalse();
        var future = crypto.newChallenge(clock.instant().plusSeconds(1));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, future))).isFalse();
        assertThat(count("email_action_challenge")).isZero();
    }

    @Test
    void queuedCandidatesUseAcceptanceTimeForCooldownWithoutExtendingTheirExpiry() {
        var first = crypto.newChallenge(clock.instant());
        var second = crypto.newChallenge(clock.instant());
        var third = crypto.newChallenge(clock.instant());
        clock.advance(Duration.ofSeconds(61));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, first))).isTrue();
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, second))).isFalse();
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, third))).isFalse();
        var row = jdbc.queryForMap("SELECT created_at,accepted_at,expires_at FROM hackatlas.email_action_challenge");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(first.createdAt());
        assertThat(((Timestamp) row.get("accepted_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(first.expiresAt());
        clock.advance(Duration.ofSeconds(60));
        assertThat(tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, second))).isTrue();
    }

    @Test
    void waitingForTheAccountLockCannotExtendAnAcknowledgedChallengeDeadline() throws Exception {
        var candidate = crypto.newChallenge(clock.instant());
        var workerPid = new AtomicInteger();
        var pending = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            tx(() -> {
                jdbc.queryForObject("SELECT id FROM hackatlas.user_account WHERE id=? FOR UPDATE", UUID.class, user);
                pending.add(executor.submit(() -> tx(() -> {
                    workerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return store.createChallenge(user, EMAIL_VERIFICATION, candidate);
                })));
                await().atMost(5, TimeUnit.SECONDS).until(() -> workerPid.get() != 0);
                await().atMost(5, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, workerPid.get()));
                clock.advance(Duration.ofMinutes(10));
                return null;
            });
            assertThat(pending.getFirst().get(5, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(count("email_action_challenge")).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void challengeExpiresAtItsExactDeadline(long microsecondsBeforeExpiry) {
        var challenge = challenge(EMAIL_VERIFICATION);
        clock.advance(Duration.ofMinutes(10).plusNanos(microsecondsBeforeExpiry * 1000));
        assertThat(tx(() -> store.consumeVerification(user, challenge.challengeId(), challenge.code())).isPresent())
                .isEqualTo(microsecondsBeforeExpiry < 0);
    }

    @Test
    void exchangesCodeForOneResetOnlyGrantAndStoresNeitherRawSecret() {
        var challenge = challenge(PASSWORD_RESET);
        var grant = tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code())).orElseThrow();
        assertThat(tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code()))).isEmpty();
        var row = jdbc.queryForMap("SELECT * FROM hackatlas.password_reset_grant");
        assertThat(row.get("token_hash")).isEqualTo(crypto.hashToken(grant.resetToken()));
        assertThat(row.values()).doesNotContain(grant.resetToken(), challenge.challengeId(), challenge.code());
        assertThat(auth.authenticate(grant.resetToken())).isEmpty();
        assertThat(tx(() -> store.consumeResetGrant(challenge.challengeId()))).isEmpty();
        assertThat(tx(() -> store.consumeResetGrant("malformed"))).isEmpty();
        var proof = tx(() -> store.consumeResetGrant(grant.resetToken())).orElseThrow();
        assertThat(proof.userId()).isEqualTo(user);
        assertThat(tx(() -> store.consumeResetGrant(grant.resetToken()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void grantExpiresAtItsExactDeadline(long microsecondsBeforeExpiry) {
        var challenge = challenge(PASSWORD_RESET);
        var grant = tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code())).orElseThrow();
        clock.advance(Duration.ofMinutes(5).plusNanos(microsecondsBeforeExpiry * 1000));
        assertThat(tx(() -> store.consumeResetGrant(grant.resetToken())).isPresent()).isEqualTo(microsecondsBeforeExpiry < 0);
    }

    @Test
    void versionChangeInvalidatesAnAlreadyIssuedGrant() {
        var challenge = challenge(PASSWORD_RESET);
        var grant = tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code())).orElseThrow();
        jdbc.update("UPDATE hackatlas.user_account SET credential_version=1 WHERE id=?", user);
        assertThat(tx(() -> store.consumeResetGrant(grant.resetToken()))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM hackatlas.password_reset_grant", Timestamp.class)).isNull();
    }

    @Test
    void concurrentChallengeAndGrantConsumptionEachHaveOneWinner() throws Exception {
        var verification = challenge(EMAIL_VERIFICATION);
        assertThat(parallel(() -> tx(() -> store.consumeVerification(user, verification.challengeId(), verification.code())).isPresent())).isEqualTo(1);
        var reset = challenge(PASSWORD_RESET);
        assertThat(parallel(() -> tx(() -> store.exchangeResetCode(reset.challengeId(), reset.code())).isPresent())).isEqualTo(1);
        assertThat(count("password_reset_grant")).isEqualTo(1);
        // A second exchange is not available; another challenge supplies an in-memory grant.
        clock.advance(Duration.ofSeconds(60));
        var next = challenge(PASSWORD_RESET);
        var grant = tx(() -> store.exchangeResetCode(next.challengeId(), next.code())).orElseThrow();
        assertThat(parallel(() -> tx(() -> store.consumeResetGrant(grant.resetToken())).isPresent())).isEqualTo(1);
    }

    @Test
    void concurrentResendsAndWrongAttemptsCannotExceedTheirLimits() throws Exception {
        var old = challenge(EMAIL_VERIFICATION);
        clock.advance(Duration.ofSeconds(60));
        assertThat(parallel(() -> tx(() -> store.createChallenge(user, EMAIL_VERIFICATION, crypto.newChallenge(clock.instant()))))).isEqualTo(1);
        parallel(() -> tx(() -> store.consumeVerification(user, old.challengeId(), wrong(old))).isPresent());
        assertThat(attempts(old)).isEqualTo(5);
        assertThat(tx(() -> store.consumeVerification(user, old.challengeId(), old.code()))).isEmpty();
    }

    @Test
    void proofAndAccountMutationRollBackTogetherAndCanThenBeCommittedAtomically() {
        var challenge = challenge(EMAIL_VERIFICATION);
        assertThatThrownBy(() -> tx(() -> {
            var proof = store.consumeVerification(user, challenge.challengeId(), challenge.code()).orElseThrow();
            jdbc.update("UPDATE hackatlas.user_account SET email_verified=true WHERE id=?", proof.userId());
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);
        assertThat(jdbc.queryForObject("SELECT email_verified FROM hackatlas.user_account WHERE id=?", Boolean.class, user)).isFalse();
        assertThat(tx(() -> {
            var proof = store.consumeVerification(user, challenge.challengeId(), challenge.code()).orElseThrow();
            return jdbc.update("UPDATE hackatlas.user_account SET email_verified=true WHERE id=?", proof.userId());
        })).isEqualTo(1);
    }

    @Test
    void failedExchangeRollsBackCodeConsumptionAndGrantInsertionTogether() {
        var challenge = challenge(PASSWORD_RESET);
        assertThatThrownBy(() -> tx(() -> {
            store.exchangeResetCode(challenge.challengeId(), challenge.code()).orElseThrow();
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);
        assertThat(count("password_reset_grant")).isZero();
        assertThat(tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code()))).isPresent();
    }

    @Test
    void failedPasswordMutationDoesNotSpendGrantOrLeaveChangedCredentials() {
        var challenge = challenge(PASSWORD_RESET);
        var grant = tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code())).orElseThrow();
        assertThatThrownBy(() -> tx(() -> {
            var proof = store.consumeResetGrant(grant.resetToken()).orElseThrow();
            jdbc.update("UPDATE hackatlas.user_account SET password_hash='synthetic-new-hash',credential_version=credential_version+1 WHERE id=?", proof.userId());
            throw new SimulatedFailure();
        })).isInstanceOf(SimulatedFailure.class);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM hackatlas.user_account WHERE id=?", String.class, user)).isEqualTo("synthetic-old-hash");
        assertThat(tx(() -> store.consumeResetGrant(grant.resetToken()))).isPresent();
    }

    @Test
    void deletionCascadesSecretsAndRecreatingTheEmailCannotReuseThem() {
        var challenge = challenge(PASSWORD_RESET);
        var grant = tx(() -> store.exchangeResetCode(challenge.challengeId(), challenge.code())).orElseThrow();
        var verification = challenge(EMAIL_VERIFICATION);
        jdbc.update("DELETE FROM hackatlas.user_account WHERE id=?", user);
        var replacement = account("owner@example.com");
        assertThat(replacement).isNotEqualTo(user);
        assertThat(count("email_action_challenge")).isZero();
        assertThat(count("password_reset_grant")).isZero();
        assertThat(tx(() -> store.consumeVerification(replacement, verification.challengeId(), verification.code()))).isEmpty();
        assertThat(tx(() -> store.consumeResetGrant(grant.resetToken()))).isEmpty();
    }

    @Test
    void missingAccountOrUnknownLegacyDeliveryAddressProducesNoChallenge() {
        var candidate = crypto.newChallenge(clock.instant());
        assertThat(tx(() -> store.createChallenge(UUID.randomUUID(), PASSWORD_RESET, candidate))).isFalse();
        jdbc.update("UPDATE hackatlas.user_account SET email_delivery_address=NULL WHERE id=?", user);
        assertThat(tx(() -> store.createChallenge(user, PASSWORD_RESET, candidate))).isFalse();
        assertThat(count("email_action_challenge")).isZero();
    }

    private EmailActionCrypto.Challenge challenge(com.hackatlas.auth.lifecycle.EmailActionPurpose purpose) {
        var value = crypto.newChallenge(clock.instant());
        assertThat(tx(() -> store.createChallenge(user, purpose, value))).isTrue();
        return value;
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hackatlas.user_account(id,email,email_delivery_address,password_hash,display_name,created_at,updated_at)
                VALUES (?,?,?,'synthetic-old-hash','Lifecycle',?,?)
                """, id, email, email, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        return id;
    }

    private int attempts(EmailActionCrypto.Challenge challenge) {
        return jdbc.queryForObject("SELECT failed_attempts FROM hackatlas.email_action_challenge WHERE id_hash=?", Integer.class,
                crypto.hashToken(challenge.challengeId()));
    }

    private int count(String table) {
        if (!table.equals("email_action_challenge") && !table.equals("password_reset_grant")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM hackatlas." + table, Integer.class);
    }

    private <T> T tx(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private int parallel(Callable<Boolean> action) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 16; index++) tasks.add(executor.submit(() -> { start.await(); return action.call(); }));
            start.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) accepted++;
            return accepted;
        }
    }

    private static String wrong(EmailActionCrypto.Challenge challenge) {
        return challenge.code().equals("00000000") ? "00000001" : "00000000";
    }

    private static final class SimulatedFailure extends RuntimeException { }

    private static final class MutableClock extends Clock {
        private volatile Instant value = Instant.parse("2026-11-03T10:00:00Z");
        void advance(Duration duration) { value = value.plus(duration); }
        @Override public Instant instant() { return value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
    }
}
