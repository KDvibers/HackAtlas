package com.hackatlas;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleApiIT extends N2HttpSupport {
    @Test
    void pendingSessionHasThirtyMinutesAndOnlyTheDocumentedPrivateAllowlist() throws Exception {
        var me = call("GET", "/me", token, null, 200);
        assertThat(me.path("emailVerified").isBoolean()).isTrue();
        assertThat(me.path("emailVerified").booleanValue()).isFalse();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM hackatlas.auth_session WHERE user_id=?", Timestamp.class, user).toInstant())
                .isEqualTo(LifecycleIntegrationConfiguration.CLOCK.instant().plus(Duration.ofMinutes(30)));
        for (var item : List.of(new String[]{"PATCH", "/me", "{}"}, new String[]{"GET", "/me/saved", null},
                new String[]{"PUT", "/me/saved/" + AI, null}, new String[]{"DELETE", "/me/saved/" + AI, null},
                new String[]{"GET", "/me/applications", null}, new String[]{"PUT", "/me/applications/" + AI, null},
                new String[]{"DELETE", "/me/applications/" + AI, null}, new String[]{"POST", "/suggestions", "{}"},
                new String[]{"POST", "/hackathons/" + AI + "/reports", "{}"})) {
            assertThat(call(item[0], item[1], token, item[2], 403).path("code").stringValue()).isEqualTo("EMAIL_NOT_VERIFIED");
        }
        request("GET", "/hackathons", "not-a-valid-bearer", null, 200);
        call("POST", "/auth/logout", token, null, 204);
        call("GET", "/me", token, null, 401);
    }

    @Test
    void realSmtpVerificationSetsTruthfulFlagWithoutExtendingTheExistingPendingSession() throws Exception {
        var before = jdbc.queryForObject("SELECT expires_at FROM hackatlas.auth_session WHERE user_id=?", Timestamp.class, user);
        var challenge = verificationRequest();
        assertThat(challenge.path("resendAfterSeconds").intValue()).isEqualTo(60);
        var code = code(email, challenge);
        var inbox = json.readTree(mailbox("GET", "/api/v1/search?query="
                + java.net.URLEncoder.encode("to:" + email, java.nio.charset.StandardCharsets.UTF_8)).body());
        assertThat(inbox.path("messages").get(0).path("To").get(0).path("Address").stringValue()).isEqualTo(delivery);
        request("POST", "/auth/email-verification/confirm", token, codePayload(challenge, code), 204);
        assertThat(call("GET", "/me", token, null, 200).path("emailVerified").booleanValue()).isTrue();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM hackatlas.auth_session WHERE user_id=?", Timestamp.class, user)).isEqualTo(before);
        call("POST", "/auth/email-verification/request", token, null, 204);
        call("PUT", "/me/saved/" + AI, token, null, 204);
        var login = call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 200);
        assertThat(login.path("user").path("emailVerified").booleanValue()).isTrue();
        assertThat(java.time.Instant.parse(login.path("expiresAt").stringValue()))
                .isEqualTo(LifecycleIntegrationConfiguration.CLOCK.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void resetChangesPasswordRevokesEveryNativeSessionAndConsumesAllProofsWithoutAutologin() throws Exception {
        var otherLogin = call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 200);
        var challenge = resetRequest(email, null);
        var code = code(email, challenge);
        var grantResponse = request("POST", "/auth/password-reset/verify", null, codePayload(challenge, code), 200);
        assertThat(grantResponse.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var grant = body(grantResponse);
        assertThat(grant.has("accessToken")).isFalse();
        assertThat(grant.has("user")).isFalse();
        String resetToken = grant.path("resetToken").stringValue();
        call("GET", "/me", resetToken, null, 401);
        String next = "  New synthetic пароль 🔐 2026  ";
        request("POST", "/auth/password-reset/confirm", null,
                json.writeValueAsString(Map.of("resetToken", resetToken, "newPassword", next)), 204);
        call("GET", "/me", token, null, 401);
        call("GET", "/me", otherLogin.path("accessToken").stringValue(), null, 401);
        assertThat(call("POST", "/auth/password-reset/verify", null, codePayload(challenge, code), 400).path("code").stringValue()).isEqualTo("EMAIL_ACTION_INVALID");
        assertThat(call("POST", "/auth/password-reset/confirm", null,
                json.writeValueAsString(Map.of("resetToken", resetToken, "newPassword", next)), 400).path("code").stringValue()).isEqualTo("EMAIL_ACTION_INVALID");
        call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 401);
        var fresh = call("POST", "/auth/login", null, loginPayload(email, next), 200);
        assertThat(fresh.path("user").path("emailVerified").booleanValue()).isTrue();
        assertThat(jdbc.queryForObject("SELECT credential_version FROM hackatlas.user_account WHERE id=?", Long.class, user)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.email_action_challenge WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hackatlas.password_reset_grant WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test
    void aValidBearerFromAnotherAccountCannotRedirectPublicRecovery() throws Exception {
        var other = register("other-" + UUID.randomUUID() + "@example.com", PASSWORD, "Other");
        String otherToken = other.path("accessToken").stringValue();
        var challenge = resetRequest(email, otherToken);
        var grant = call("POST", "/auth/password-reset/verify", otherToken, codePayload(challenge, code(email, challenge)), 200);
        call("POST", "/auth/password-reset/confirm", otherToken, json.writeValueAsString(Map.of(
                "resetToken", grant.path("resetToken").stringValue(), "newPassword", "new-synthetic-passphrase-2026")), 204);
        call("GET", "/me", otherToken, null, 200);
        call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 401);
        call("POST", "/auth/login", null, loginPayload(email, "new-synthetic-passphrase-2026"), 200);
    }

    @Test
    void wrongCodeIs400DoesNotLogoutAndTheFifthFailedAttemptSurvivesHttpTransactions() throws Exception {
        var challenge = verificationRequest();
        String actual = code(email, challenge);
        String wrong = actual.equals("00000000") ? "00000001" : "00000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            var response = request("POST", "/auth/email-verification/confirm", token, codePayload(challenge, wrong), 400);
            assertThat(body(response).path("code").stringValue()).isEqualTo("EMAIL_ACTION_INVALID");
            assertThat(response.headers().allValues("WWW-Authenticate")).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM hackatlas.email_action_challenge WHERE user_id=?", Integer.class, user)).isEqualTo(5);
        call("POST", "/auth/email-verification/confirm", token, codePayload(challenge, actual), 400);
        call("GET", "/me", token, null, 200);
    }

    @Test
    void wrongOwnerAndPurposeDoNotSpendVerificationAndVerifiedRequestNeedsNoBody() throws Exception {
        var challenge = verificationRequest();
        var code = code(email, challenge);
        var other = register("other-" + UUID.randomUUID() + "@example.com", PASSWORD, "Other");
        assertThat(call("POST", "/auth/email-verification/confirm", other.path("accessToken").stringValue(),
                codePayload(challenge, code), 400).path("code").stringValue()).isEqualTo("EMAIL_ACTION_INVALID");
        call("POST", "/auth/password-reset/verify", null, codePayload(challenge, code), 400);
        call("POST", "/auth/email-verification/confirm", token, codePayload(challenge, code), 204);
        call("POST", "/auth/email-verification/request", token, "{}", 400);
        call("POST", "/auth/email-verification/request", token, null, 204);
    }

    @Test
    void cooldownHasRetryAfterAndExpiredCodeDoesNotRevokeTheBearer() throws Exception {
        var challenge = verificationRequest();
        var code = code(email, challenge);
        var limited = request("POST", "/auth/email-verification/request", token, null, 429);
        assertThat(limited.headers().firstValue("Retry-After").orElse("")).isEqualTo("60");
        LifecycleIntegrationConfiguration.CLOCK.advance(Duration.ofMinutes(10));
        call("POST", "/auth/email-verification/confirm", token, codePayload(challenge, code), 400);
        call("GET", "/me", token, null, 200);
    }

    @Test
    void passwordResetRequiredAppearsOnlyAtLoginAndRecoveryClearsIt() throws Exception {
        jdbc.update("UPDATE hackatlas.user_account SET password_reset_required=true WHERE id=?", user);
        assertThat(call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 403).path("code").stringValue()).isEqualTo("PASSWORD_RESET_REQUIRED");
        assertThat(call("GET", "/me", token, null, 401).path("code").stringValue()).isEqualTo("AUTH_REQUIRED");
        var challenge = resetRequest(email, null);
        var grant = call("POST", "/auth/password-reset/verify", null, codePayload(challenge, code(email, challenge)), 200);
        call("POST", "/auth/password-reset/confirm", null, json.writeValueAsString(Map.of(
                "resetToken", grant.path("resetToken").stringValue(), "newPassword", "recovered-synthetic-passphrase")), 204);
        call("POST", "/auth/login", null, loginPayload(email, "recovered-synthetic-passphrase"), 200);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void validationRejectsSecretMassAssignmentAndNeverLogsBodyCredentials(CapturedOutput output) throws Exception {
        var malformed = call("POST", "/auth/password-reset/request", null,
                json.writeValueAsString(Map.of("email", email, "userId", user.toString())), 400);
        assertThat(malformed.path("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        var challenge = resetRequest(email, null);
        var code = code(email, challenge);
        var grant = call("POST", "/auth/password-reset/verify", null, codePayload(challenge, code), 200);
        call("POST", "/auth/password-reset/confirm", null, json.writeValueAsString(Map.of(
                "resetToken", grant.path("resetToken").stringValue(), "newPassword", "new-synthetic-passphrase-2026",
                "emailVerified", true)), 400);
        assertThat(output.getAll()).doesNotContain(email, token, code, grant.path("resetToken").stringValue());
    }
}
