package com.hackatlas;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.hackatlas.catalog.CatalogDemoSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=10000",
                "hackatlas.auth.rate-limit.account-limit=10000", "hackatlas.moderation.rate-limit.ip-limit=10000",
                "hackatlas.moderation.rate-limit.account-limit=10000"})
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationApiIT extends B2HttpSupport {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogDemoSeeder seeder;
    private String email;
    private String token;
    private UUID userId;

    @Override String contractPhase() { return "B3"; }

    @BeforeEach
    void freshAccountsAndCatalogue() throws Exception {
        jdbc.execute("truncate table hackatlas.user_account, hackatlas.catalog_hackathon, hackatlas.catalog_tag, hackatlas.account_deletion_receipt cascade");
        seeder.seed();
        email = "moderation-" + UUID.randomUUID() + "@example.com";
        var auth = register(email, PASSWORD, "Тест модерации");
        token = auth.path("accessToken").stringValue();
        userId = UUID.fromString(auth.path("user").path("id").stringValue());
    }

    @Test
    void suggestionsAndReportsEnterPendingQueueWithoutPublishingOrChangingTheEvent() throws Exception {
        var before = jdbc.queryForMap("select title,source_url,last_verified_at,updated_at,publication_status from hackatlas.catalog_hackathon where id=?", UUID.fromString(AI));
        var suggestion = submitSuggestion("https://example.com/new-hackathon", "Предлагаю проверить источник");
        var report = call("POST", reportPath(AI), token, reportPayload("DATES", "Проверьте дату начала"), 202);
        assertReceipt(suggestion);
        assertReceipt(report);
        assertThat(suggestion.path("id")).isNotEqualTo(report.path("id"));
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_hackathon", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForMap("select title,source_url,last_verified_at,updated_at,publication_status from hackatlas.catalog_hackathon where id=?", UUID.fromString(AI)))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.moderation_submission where user_id=? and status='PENDING'", Integer.class, userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select hackathon_id from hackatlas.moderation_submission where id=?", UUID.class,
                UUID.fromString(report.path("id").stringValue()))).isEqualTo(UUID.fromString(AI));
    }

    @Test
    void nullableCommentIsStoredAsNullAndHttpsUrlIsNeverDownloaded() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(150);
            var url = "https://127.0.0.1:" + listener.getLocalPort() + "/private-source";
            var receipt = call("POST", "/suggestions", token,
                    json.writeValueAsString(Map.of("url", url)).replace("}", ",\"comment\":null}"), 202);
            assertReceipt(receipt);
            assertThatThrownBy(listener::accept).isInstanceOf(SocketTimeoutException.class);
            var row = jdbc.queryForMap("select url,comment from hackatlas.moderation_submission where id=?",
                    UUID.fromString(receipt.path("id").stringValue()));
            assertThat(row.get("url")).isEqualTo(url);
            assertThat(row.get("comment")).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a", "javascript:alert(1)", "data:text/html,test", "file:///etc/passwd",
            "https://user:password@example.com/event", "https://user@example.com/event", "https:///missing-host",
            "https://example.com:99999/event", "https://example.com/a b", "https://example.com/\\evil",
            "HTTPS://example.com/event", "https://example.com/%zz"})
    void unsafeOrMalformedSourceUrlsAreRejected(String url) throws Exception {
        call("POST", "/suggestions", token, json.writeValueAsString(Map.of("url", url, "comment", "test")), 400);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.moderation_submission", Integer.class)).isZero();
    }

    static Stream<String> invalidSuggestions() {
        return Stream.of("{}", "null", "[]", "{\"url\":\"https://example.com/event\"}",
                "{\"url\":null,\"comment\":null}", "{\"url\":13,\"comment\":null}",
                "{\"url\":\"https://example.com/event\",\"comment\":42}",
                "{\"url\":\"https://example.com/event\",\"comment\":\"" + "я".repeat(2001) + "\"}",
                "{\"url\":\"https://example.com/event\",\"comment\":null,\"status\":\"PUBLISHED\"}",
                "{\"url\":\"https://example.com/event\",\"comment\":null,\"userId\":\"" + UNKNOWN + "\"}",
                "{\"url\":\"https://example.com/event\",\"comment\":null,\"comment\":\"duplicate\"}",
                "{\"url\":\"https://example.com/event\",\"comment\":\"\\u0000\"}", "{bad json}");
    }

    @ParameterizedTest
    @MethodSource("invalidSuggestions")
    void suggestionInputIsStrictAndCannotAssignModerationStateOrOwner(String payload) throws Exception {
        call("POST", "/suggestions", token, payload, 400);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.moderation_submission", Integer.class)).isZero();
    }

    static Stream<String> invalidReports() {
        return Stream.of("{}", "null", "[]", "{\"reason\":\"DATES\"}",
                "{\"reason\":\"DATES\",\"message\":null}", "{\"reason\":\"dates\",\"message\":\"Valid message\"}",
                "{\"reason\":\"OTHER\",\"message\":\"four\"}",
                "{\"reason\":\"OTHER\",\"message\":\"" + "я".repeat(2001) + "\"}",
                "{\"reason\":\"OTHER\",\"message\":\"Valid message\",\"hackathonId\":\"" + UNKNOWN + "\"}",
                "{\"reason\":\"OTHER\",\"message\":\"Valid message\",\"status\":\"RESOLVED\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidReports")
    void reportInputIsStrict(String payload) throws Exception {
        call("POST", reportPath(AI), token, payload, 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATES", "LINK", "PRIZE", "ELIGIBILITY", "DUPLICATE", "OTHER"})
    void allContractReportReasonsAreAccepted(String reason) throws Exception {
        assertReceipt(call("POST", reportPath(AI), token, reportPayload(reason, "Достаточно подробное описание"), 202));
    }

    @Test
    void commentsAndMessagesUseUnicodeCodePointLimits() throws Exception {
        assertReceipt(submitSuggestion("https://example.com/unicode", "🚀".repeat(2000)));
        assertReceipt(call("POST", reportPath(AI), token, reportPayload("OTHER", "🚀".repeat(2000)), 202));
        call("POST", reportPath(AI), token, reportPayload("OTHER", "🚀".repeat(2001)), 400);
    }

    @Test
    void reportsRequireAnExistingPublishedEventAndTheIdIsValidated() throws Exception {
        call("POST", reportPath("invalid"), token, reportPayload("OTHER", "Проверьте событие"), 400);
        call("POST", reportPath(UNKNOWN), token, reportPayload("OTHER", "Проверьте событие"), 404);
        for (var visibility : List.of("DRAFT", "ARCHIVED")) {
            jdbc.update("update hackatlas.catalog_hackathon set publication_status=? where id=?", visibility, UUID.fromString(AI));
            call("POST", reportPath(AI), token, reportPayload("OTHER", "Проверьте событие"), 404);
        }
        jdbc.update("update hackatlas.catalog_hackathon set publication_status='PUBLISHED',event_status='CANCELLED' where id=?", UUID.fromString(AI));
        assertReceipt(call("POST", reportPath(AI), token, reportPayload("DATES", "Проверьте отмену события"), 202));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/suggestions", "/hackathons/39287368-5259-5235-a1c4-25b4e1e8421e/reports", "/me/delete-account"})
    void allThreeOperationsRejectAnonymousAndUnknownSessions(String path) throws Exception {
        call("POST", path, null, "{}", 401);
        call("POST", path, "Z".repeat(43), "{}", 401);
    }

    @Test
    void moderationRejectsUnsupportedMediaAndOversizedRequestsWithoutPartialSubmission() throws Exception {
        request("POST", "/suggestions", token, "{}", 400, Map.of("Content-Type", "text/plain"));
        call("POST", "/suggestions", token, json.writeValueAsString(Map.of(
                "url", "https://example.com/event", "comment", "X".repeat(100_000))), 400);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.moderation_submission", Integer.class)).isZero();
    }

    static Stream<String> invalidDeletionBodies() {
        return Stream.of("{}", "null", "[]", "{\"password\":null}", "{\"password\":\"\"}",
                "{\"password\":123}", "{\"password\":\"" + "x".repeat(257) + "\"}",
                "{\"password\":\"anything\",\"userId\":\"" + UNKNOWN + "\"}",
                "{\"password\":\"first\",\"password\":\"second\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidDeletionBodies")
    void deletionValidatesStrictPasswordOnlyBody(String payload) throws Exception {
        call("POST", "/me/delete-account", token, payload, 400);
        call("GET", "/me", token, null, 200);
    }

    @Test
    void wrongOrTrimmedPasswordCannotDeleteAndTheErrorIsGeneric() throws Exception {
        var wrong = call("POST", "/me/delete-account", token, deletePayload(PASSWORD.strip()), 401);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.account_deletion_receipt where account_id=?", Integer.class, userId)).isZero();
        assertThat(wrong.path("code").stringValue()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrong.toString().contains(email)).isFalse();
        assertThat(wrong.toString().contains(PASSWORD)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.user_account where id=?", Integer.class, userId)).isEqualTo(1);
        call("GET", "/me", token, null, 200);
    }

    @Test
    void deletionRemovesAllPrivateRelationshipsAndSessionsWhileOtherAccountsSurvive() throws Exception {
        var secondSession = call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 200).path("accessToken").stringValue();
        var other = register("remaining@example.com", PASSWORD, "Оставшийся пользователь");
        var otherToken = other.path("accessToken").stringValue();
        var otherId = UUID.fromString(other.path("user").path("id").stringValue());
        for (var bearer : List.of(token, otherToken)) {
            call("PATCH", "/me", bearer, "{\"interests\":[\"java\"]}", 200);
            call("PUT", "/me/saved/" + AI, bearer, null, 204);
            call("PUT", "/me/applications/" + AI, bearer, null, 200);
            call("POST", "/suggestions", bearer, "{\"url\":\"https://example.com/event\",\"comment\":null}", 202);
            call("POST", reportPath(AI), bearer, reportPayload("OTHER", "Проверьте событие"), 202);
        }
        jdbc.update("insert into hackatlas.admin_access(user_id) values(?)", userId);
        call("POST", "/me/delete-account", token, deletePayload(PASSWORD), 204);
        for (var table : List.of("user_account", "auth_session", "user_interest", "saved_hackathon",
                "application_mark", "moderation_submission", "admin_access")) {
            String column = table.equals("user_account") ? "id" : "user_id";
            assertThat(jdbc.queryForObject("select count(*) from hackatlas." + table + " where " + column + "=?", Integer.class, userId))
                    .as("deleted user's %s rows", table).isZero();
        }
        call("GET", "/me", token, null, 401);
        call("GET", "/me", secondSession, null, 401);
        call("POST", "/me/delete-account", token, deletePayload(PASSWORD), 401);
        call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 401);
        assertThat(call("GET", "/me", otherToken, null, 200).path("id").stringValue()).isEqualTo(otherId.toString());
        assertThat(call("GET", "/me/saved", otherToken, null, 200).path("items").size()).isEqualTo(1);
        assertThat(call("GET", "/me/applications", otherToken, null, 200).path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.moderation_submission where user_id=?", Integer.class, otherId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_hackathon", Integer.class)).isEqualTo(5);
        var audit = String.join("", jdbc.queryForList("select row_to_json(a)::text from hackatlas.admin_audit a", String.class));
        assertThat(audit).contains("ACCOUNT_DELETED");
        for (var privateValue : List.of(email, token, PASSWORD)) assertThat(audit.contains(privateValue)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.account_deletion_receipt where account_id=?", Integer.class, userId)).isEqualTo(1);
        var receipt = jdbc.queryForMap("select * from hackatlas.account_deletion_receipt where account_id=?", userId);
        assertThat(receipt.keySet()).containsExactlyInAnyOrder("id", "account_id", "recorded_at");
        assertThat(receipt.get("recorded_at")).isNotNull();
        assertThat(audit).doesNotContain(userId.toString());
        for (var privateValue : List.of(email, token, PASSWORD)) assertThat(receipt.toString()).doesNotContain(privateValue);
        var replacement = register(email, PASSWORD, "Новая регистрация");
        var replacementId = UUID.fromString(replacement.path("user").path("id").stringValue());
        assertThat(replacementId).isNotEqualTo(userId);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.account_deletion_receipt where account_id=?", Integer.class, replacementId)).isZero();
    }

    @Test
    void racingLoginCannotLeaveAValidSessionAfterDeletion() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var http = HttpClient.newHttpClient()) {
            var logins = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int index = 0; index < 2; index++) {
                logins.add(executor.submit(() -> {
                    start.await();
                    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort() + "/api/v1/auth/login"))
                                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(loginPayload(email, PASSWORD))).build(),
                            HttpResponse.BodyHandlers.ofString());
                }));
            }
            var deletion = executor.submit(() -> {
                start.await();
                return request("POST", "/me/delete-account", token, deletePayload(PASSWORD), 204);
            });
            start.countDown();
            deletion.get();
            for (var login : logins) {
                var response = login.get();
                assertThat(response.statusCode()).isIn(200, 401);
                if (response.statusCode() == 200) call("GET", "/me", body(response).path("accessToken").stringValue(), null, 401);
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.auth_session where user_id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.user_account where id=?", Integer.class, userId)).isZero();
    }

    static Stream<Arguments> databaseFaults() {
        return Stream.of(Arguments.of(500, "23514"), Arguments.of(503, "08006"));
    }

    @ParameterizedTest
    @MethodSource("databaseFaults")
    @ExtendWith(OutputCaptureExtension.class)
    void databaseFailuresReturnSanitisedModerationProblemsAndRequestsRecover(int status, String sqlState,
                                                                             CapturedOutput output) throws Exception {
        installFault("moderation_submission", sqlState);
        try {
            var suggestion = call("POST", "/suggestions", token,
                    "{\"url\":\"https://example.com/fault\",\"comment\":null}", status);
            var report = call("POST", reportPath(AI), token, reportPayload("OTHER", "Проверьте источник"), status);
            assertSafeFailure(suggestion, status, output);
            assertSafeFailure(report, status, output);
        } finally {
            removeFault("moderation_submission");
        }
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.moderation_submission", Integer.class)).isZero();
        assertReceipt(submitSuggestion("https://example.com/recovered", "Сервис восстановлен"));
        assertReceipt(call("POST", reportPath(AI), token, reportPayload("OTHER", "Сервис восстановлен"), 202));
    }

    @ParameterizedTest
    @MethodSource("databaseFaults")
    @ExtendWith(OutputCaptureExtension.class)
    void auditFailureAfterDeletionRollsBackTheAccountEverySessionAndAllPrivateLinks(int status, String sqlState,
                                                                                  CapturedOutput output) throws Exception {
        var secondToken = call("POST", "/auth/login", null, loginPayload(email, PASSWORD), 200).path("accessToken").stringValue();
        call("PATCH", "/me", token, "{\"interests\":[\"java\"]}", 200);
        call("PUT", "/me/saved/" + AI, token, null, 204);
        call("PUT", "/me/applications/" + AI, token, null, 200);
        submitSuggestion("https://example.com/preserved", "Запись должна восстановиться");
        jdbc.update("insert into hackatlas.admin_access(user_id) values(?)", userId);
        // AccountDeletionService writes this audit only after DELETE + all cascading private deletes.
        installFault("admin_audit", sqlState);
        try {
            assertSafeFailure(call("POST", "/me/delete-account", token, deletePayload(PASSWORD), status), status, output);
        } finally {
            removeFault("admin_audit");
        }
        for (var table : List.of("user_account", "user_interest", "saved_hackathon", "application_mark", "moderation_submission", "admin_access")) {
            String column = table.equals("user_account") ? "id" : "user_id";
            assertThat(jdbc.queryForObject("select count(*) from hackatlas." + table + " where " + column + "=?", Integer.class, userId))
                    .as("rollback restores %s", table).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.auth_session where user_id=?", Integer.class, userId)).isEqualTo(2);
        call("GET", "/me", token, null, 200);
        call("GET", "/me", secondToken, null, 200);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.admin_audit where action='ACCOUNT_DELETED'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.account_deletion_receipt where account_id=?", Integer.class, userId)).isZero();
        call("POST", "/me/delete-account", token, deletePayload(PASSWORD), 204);
        call("GET", "/me", secondToken, null, 401);
    }

    @ParameterizedTest
    @MethodSource("databaseFaults")
    @ExtendWith(OutputCaptureExtension.class)
    void receiptFailureRollsBackDeletionAndAudit(int status, String sqlState, CapturedOutput output) throws Exception {
        call("PUT", "/me/saved/" + AI, token, null, 204);
        installFault("account_deletion_receipt", sqlState);
        try {
            assertSafeFailure(call("POST", "/me/delete-account", token, deletePayload(PASSWORD), status), status, output);
        } finally {
            removeFault("account_deletion_receipt");
        }
        for (var table : List.of("user_account", "auth_session", "saved_hackathon")) {
            String column = table.equals("user_account") ? "id" : "user_id";
            assertThat(jdbc.queryForObject("select count(*) from hackatlas." + table + " where " + column + "=?", Integer.class, userId)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.account_deletion_receipt where account_id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.admin_audit where action='ACCOUNT_DELETED'", Integer.class)).isZero();
        call("GET", "/me", token, null, 200);
        call("POST", "/me/delete-account", token, deletePayload(PASSWORD), 204);
        assertThat(output.getAll()).doesNotContain(userId.toString(), email, token, PASSWORD);
    }

    @Test
    void concurrentDeletionRecordsOnlyOneReceipt() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var http = HttpClient.newHttpClient()) {
            var attempts = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (int index = 0; index < 2; index++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort() + "/api/v1/me/delete-account"))
                            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString(deletePayload(PASSWORD))).build(), HttpResponse.BodyHandlers.ofString());
                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var attempt : attempts) statuses.add(attempt.get().statusCode());
            assertThat(statuses).containsExactlyInAnyOrder(204, 401);
        }
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.account_deletion_receipt where account_id=?", Integer.class, userId)).isEqualTo(1);
    }

    private void installFault(String table, String sqlState) {
        // Controlled test-only identifiers/states; never run against a configured development/production DB.
        jdbc.execute("create function hackatlas.qa_b3_failure() returns trigger language plpgsql as $$ begin "
                + "raise exception using errcode='" + sqlState + "', message='QA_B3_PRIVATE_DATABASE_FAILURE'; end; $$");
        jdbc.execute("create trigger qa_b3_failure before insert on hackatlas." + table
                + " for each row execute function hackatlas.qa_b3_failure()");
    }

    private void removeFault(String table) {
        jdbc.execute("drop trigger if exists qa_b3_failure on hackatlas." + table);
        jdbc.execute("drop function if exists hackatlas.qa_b3_failure()");
    }

    private void assertSafeFailure(JsonNode problem, int status, CapturedOutput output) {
        assertThat(problem.path("code").stringValue()).isEqualTo(status == 503 ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR");
        for (var hidden : List.of("QA_B3_PRIVATE_DATABASE_FAILURE", "qa_b3_failure", email, token, PASSWORD)) {
            assertThat(problem.toString().contains(hidden)).as("internal database details stay private").isFalse();
            assertThat(output.getAll().contains(hidden)).as("database failure logs must not contain private details").isFalse();
        }
        assertThat(output.getAll().contains(problem.path("requestId").stringValue()))
                .as("safe diagnostics retain the request ID").isTrue();
        assertThat(output.getAll().contains(status == 503 ? "JpaSystemException" : "DataIntegrityViolationException"))
                .as("safe diagnostics retain exception class names").isTrue();
    }

    private JsonNode submitSuggestion(String url, String comment) throws Exception {
        return call("POST", "/suggestions", token, json.writeValueAsString(Map.of("url", url, "comment", comment)), 202);
    }
    private String reportPayload(String reason, String message) { return json.writeValueAsString(Map.of("reason", reason, "message", message)); }
    private String deletePayload(String password) { return json.writeValueAsString(Map.of("password", password)); }
    private static String reportPath(String id) { return "/hackathons/" + id + "/reports"; }
    private static void assertReceipt(JsonNode receipt) {
        assertThat(UUID.fromString(receipt.path("id").stringValue())).isNotNull();
        assertThat(receipt.path("status").stringValue()).isEqualTo("PENDING");
        assertThat(receipt.size()).isEqualTo(2);
    }
}
