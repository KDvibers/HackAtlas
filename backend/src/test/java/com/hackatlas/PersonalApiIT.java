package com.hackatlas;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.hackatlas.catalog.CatalogDemoSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=10000",
                "hackatlas.auth.rate-limit.account-limit=10000"})
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonalApiIT extends B2HttpSupport {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CatalogDemoSeeder seeder;

    private String token;
    private String email;
    private UUID userId;

    @BeforeEach
    void freshAccountsAndCatalogue() throws Exception {
        // Only the disposable Testcontainers database is reset, including new personal FKs.
        jdbc.execute("truncate table hackatlas.user_account, hackatlas.catalog_hackathon, "
                + "hackatlas.catalog_tag cascade");
        seeder.seed();
        email = "user-" + UUID.randomUUID() + "@example.com";
        var auth = register(email, PASSWORD, "Тестовый разработчик");
        token = auth.path("accessToken").stringValue();
        userId = UUID.fromString(auth.path("user").path("id").stringValue());
    }

    @Test
    void registrationIssuesOpaqueAbsoluteSessionAndStoresOnlyHashes() throws Exception {
        assertThat(Base64.getUrlDecoder().decode(token)).hasSizeGreaterThanOrEqualTo(32);
        assertThat(token).doesNotContain(".");
        var account = jdbc.queryForMap("select * from hackatlas.user_account where id = ?", userId);
        assertThat(account.get("password_hash").toString()).contains("argon2id").doesNotContain(PASSWORD);
        var session = jdbc.queryForMap("select * from hackatlas.auth_session where user_id = ?", userId);
        assertThat(session.get("token_hash").toString()).isEqualTo(tokenHash(token));
        assertThat(session.values()).doesNotContain(token, PASSWORD);
        assertThat(((Timestamp) session.get("created_at")).toInstant()).isEqualTo(IntegrationTestConfiguration.NOW);
        assertThat(((Timestamp) session.get("expires_at")).toInstant())
                .isEqualTo(IntegrationTestConfiguration.NOW.plus(Duration.ofDays(30)));
        assertThat(session.get("revoked_at")).isNull();
        var profile = call("GET", "/me", token, null, 200);
        assertThat(profile.path("id").stringValue()).isEqualTo(userId.toString());
        assertThat(profile.path("email").stringValue()).isEqualTo(email);
        assertThat(profile.path("city").isNull()).isTrue();
        assertThat(profile.path("countryCode").isNull()).isTrue();
        assertThat(profile.path("roleLabel").isNull()).isTrue();
        assertThat(profile.path("avatarKey").stringValue()).isEqualTo("HOODED");
        assertThat(profile.path("interests").isEmpty()).isTrue();
        assertStats(profile, 0, 0, 0);
    }

    @Test
    void passwordWhitespaceIsSignificantAndNewLoginCreatesAnIndependentSession() throws Exception {
        var failure = call("POST", "/auth/login", null, loginPayload(email, PASSWORD.strip()), 401);
        assertThat(failure.path("code").stringValue()).isEqualTo("INVALID_CREDENTIALS");
        var response = request("POST", "/auth/login", null, loginPayload(email.toUpperCase(), PASSWORD), 200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var loggedIn = body(response);
        assertThat(loggedIn.path("tokenType").stringValue()).isEqualTo("Bearer");
        var secondToken = loggedIn.path("accessToken").stringValue();
        assertThat(secondToken.equals(token)).isFalse();
        assertThat(loggedIn.path("user").path("id").stringValue()).isEqualTo(userId.toString());
        call("POST", "/auth/logout", token, null, 204);
        call("GET", "/me", token, null, 401);
        call("POST", "/auth/logout", token, null, 401);
        call("GET", "/me", secondToken, null, 200);
        assertThat(jdbc.queryForObject("select revoked_at is not null from hackatlas.auth_session where token_hash = ?",
                Boolean.class, tokenHash(token))).isTrue();
    }

    @Test
    void wrongPasswordAndUnknownEmailReturnTheSameGenericError() throws Exception {
        var wrong = call("POST", "/auth/login", null, loginPayload(email, "Wrong-password-2026"), 401);
        var unknown = call("POST", "/auth/login", null,
                loginPayload("missing@example.com", "Wrong-password-2026"), 401);
        for (var field : List.of("type", "title", "status", "detail", "code", "fieldErrors")) {
            assertThat(wrong.path(field)).isEqualTo(unknown.path(field));
        }
        assertThat(wrong.toString()).doesNotContain(email, "Wrong-password-2026");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void authenticationAndValidationDoNotLogCredentialsOrPersonalData(CapturedOutput output) throws Exception {
        var privateEmail = "private-" + UUID.randomUUID() + "@example.com";
        var privatePassword = "Private-password-" + UUID.randomUUID();
        var auth = register(privateEmail, privatePassword, "Private profile");
        var privateToken = auth.path("accessToken").stringValue();
        call("POST", "/auth/login", null, loginPayload(privateEmail, privatePassword + "-wrong"), 401);
        call("POST", "/auth/login", null, json.writeValueAsString(Map.of(
                "email", privateEmail, "password", privatePassword, "extra", "invalid")), 400);
        call("GET", "/me", privateToken, null, 200);
        call("POST", "/auth/logout", privateToken, null, 204);
        call("GET", "/me", privateToken, null, 401);
        for (var sensitive : List.of(privateEmail, privatePassword, privateToken)) {
            assertThat(output.getAll().contains(sensitive)).as("captured logs must not contain credentials or PII").isFalse();
        }
    }

    @Test
    void sameNormalizedEmailCannotCreateAnotherAccount() throws Exception {
        var response = call("POST", "/auth/register", null, json.writeValueAsString(Map.of(
                "email", email.toUpperCase(), "password", PASSWORD, "displayName", "Duplicate")), 409);
        assertThat(response.path("code").stringValue()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.user_account", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiryIsAbsoluteAndBoundaryInstantIsAlreadyExpired() throws Exception {
        var expiry = IntegrationTestConfiguration.NOW.plusSeconds(1);
        jdbc.update("update hackatlas.auth_session set expires_at = ? where token_hash = ?",
                Timestamp.from(expiry), tokenHash(token));
        call("GET", "/me", token, null, 200);
        assertThat(jdbc.queryForObject("select expires_at from hackatlas.auth_session where token_hash = ?",
                Timestamp.class, tokenHash(token)).toInstant()).isEqualTo(expiry);
        jdbc.update("update hackatlas.auth_session set created_at = ?, expires_at = ? where token_hash = ?",
                Timestamp.from(IntegrationTestConfiguration.NOW.minusSeconds(1)),
                Timestamp.from(IntegrationTestConfiguration.NOW), tokenHash(token));
        call("GET", "/me", token, null, 401);
        call("POST", "/auth/logout", token, null, 401);
    }

    @Test
    void tokenInQueryOrCookieDoesNotAuthenticateAndBasicIsNotAccepted() throws Exception {
        // request paths may contain a token for this test; support never exports them.
        request("GET", "/me?access_token=" + token, null, null, 401);
        request("GET", "/me", null, null, 401, Map.of("Cookie", "accessToken=" + token));
        request("GET", "/me", null, null, 401, Map.of("Authorization", "Basic dGVzdDp0ZXN0"));
        request("GET", "/me", null, null, 401, Map.of("Authorization", "Bearer invalid-token"));
        request("GET", "/me", null, null, 401,
                Map.of("Authorization", "Bearer " + token + ", Bearer " + token));
    }

    static Stream<Arguments> protectedEndpoints() {
        return Stream.of(Arguments.of("GET", "/me"), Arguments.of("PATCH", "/me"),
                Arguments.of("POST", "/auth/logout"), Arguments.of("GET", "/me/saved"),
                Arguments.of("PUT", "/me/saved/" + AI), Arguments.of("DELETE", "/me/saved/" + AI),
                Arguments.of("GET", "/me/applications"), Arguments.of("PUT", "/me/applications/" + AI),
                Arguments.of("DELETE", "/me/applications/" + AI));
    }

    @ParameterizedTest
    @MethodSource("protectedEndpoints")
    void personalEndpointsRejectMissingAndUnknownSessions(String method, String path) throws Exception {
        call(method, path, null, "PATCH".equals(method) ? "{\"displayName\":\"Other\"}" : null, 401);
        call(method, path, "Z".repeat(43), null, 401);
    }

    @Test
    void patchPreservesAbsentFieldsAndClearsOnlyExplicitNullableFields() throws Exception {
        var initial = call("PATCH", "/me", token, """
                {"displayName":"Новый профиль","city":"Астана","countryCode":"KZ",
                 "roleLabel":"Разработчик","avatarKey":"INITIALS","interests":["java","ai-ml","backend"]}
                """, 200);
        assertThat(initial.path("city").stringValue()).isEqualTo("Астана");
        assertStats(initial, 0, 0, 3);
        var partial = call("PATCH", "/me", token, "{\"displayName\":\"Переименован\"}", 200);
        for (var field : List.of("id", "email", "city", "countryCode", "roleLabel", "avatarKey", "interests")) {
            assertThat(partial.path(field)).isEqualTo(initial.path(field));
        }
        var cleared = call("PATCH", "/me", token,
                "{\"city\":null,\"countryCode\":null,\"roleLabel\":null,\"interests\":[]}", 200);
        assertThat(cleared.path("city").isNull()).isTrue();
        assertThat(cleared.path("countryCode").isNull()).isTrue();
        assertThat(cleared.path("roleLabel").isNull()).isTrue();
        assertThat(cleared.path("displayName").stringValue()).isEqualTo("Переименован");
        assertThat(cleared.path("avatarKey").stringValue()).isEqualTo("INITIALS");
        assertStats(cleared, 0, 0, 0);
    }

    static Stream<String> invalidPatches() {
        return Stream.of("{}", "null", "[]", "{\"displayName\":null}", "{\"displayName\":\"\"}",
                "{\"displayName\":23}", "{\"displayName\":\"" + "я".repeat(61) + "\"}",
                "{\"city\":\"" + "я".repeat(101) + "\"}", "{\"countryCode\":\"kz\"}",
                "{\"countryCode\":\"KAZ\"}", "{\"avatarKey\":null}", "{\"avatarKey\":\"REMOTE\"}",
                "{\"interests\":null}", "{\"interests\":[\"java\",\"java\"]}",
                "{\"interests\":[\"not-a-real-tag\"]}", "{\"interests\":[42]}",
                "{\"role\":\"ADMIN\"}", "{\"admin\":true}", "{\"email\":\"other@example.com\"}",
                "{\"id\":\"" + UNKNOWN + "\"}", "{\"stats\":{\"savedCount\":100}}",
                "{\"displayName\":\"First\",\"displayName\":\"Second\"}",
                "{\"displayName\":\"ChangesMustRollback\",\"interests\":[\"not-a-real-tag\"]}",
                "{\"city\":\"\\u0000\"}", "{\"city\":\"\\ud800\"}", "{bad json}");
    }

    @ParameterizedTest
    @MethodSource("invalidPatches")
    void profileRejectsInvalidFieldsAndMassAssignmentWithoutPartialUpdates(String payload) throws Exception {
        var before = call("GET", "/me", token, null, 200);
        call("PATCH", "/me", token, payload, 400);
        assertThat(call("GET", "/me", token, null, 200)).isEqualTo(before);
    }

    @Test
    void unicodeLengthIsMeasuredInCodePoints() throws Exception {
        var displayName = "🚀".repeat(60);
        var changed = call("PATCH", "/me", token,
                json.writeValueAsString(Map.of("displayName", displayName)), 200);
        assertThat(changed.path("displayName").stringValue()).isEqualTo(displayName);
        call("PATCH", "/me", token, json.writeValueAsString(Map.of("displayName", displayName + "🚀")), 400);
        var longPassword = "🔐".repeat(256);
        var registered = register("unicode@example.com", longPassword, "Unicode");
        var loggedIn = call("POST", "/auth/login", null, loginPayload("unicode@example.com", longPassword), 200);
        assertThat(loggedIn.path("user").path("id")).isEqualTo(registered.path("user").path("id"));
    }

    @Test
    void emailLengthIsValidatedAfterUnicodeCaseExpansion() throws Exception {
        String expanding = "İ".repeat(32) + "@" + "a".repeat(63) + "." + "b".repeat(63)
                + "." + "c".repeat(63) + "." + "d".repeat(29);
        assertThat(expanding.length()).isEqualTo(254);
        call("POST", "/auth/register", null, json.writeValueAsString(Map.of(
                "email", expanding, "password", PASSWORD, "displayName", "Unicode")), 400);
        call("POST", "/auth/login", null, loginPayload(expanding, PASSWORD), 400);
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.user_account", Integer.class)).isEqualTo(1);
    }

    @Test
    void existingTagMustBeExplicitlyAllowedAsAProfileInterest() throws Exception {
        jdbc.update("update hackatlas.catalog_tag set interest_allowed = false where id = 'java'");
        call("PATCH", "/me", token, "{\"interests\":[\"java\"]}", 400);
        assertStats(call("GET", "/me", token, null, 200), 0, 0, 0);
    }

    static Stream<Arguments> invalidAuthBodies() {
        return Stream.of(
                Arguments.of("/auth/register", "{}"), Arguments.of("/auth/register", "null"),
                Arguments.of("/auth/register", "[]"),
                Arguments.of("/auth/register", "{\"email\":\"bad-email\",\"password\":\"123456789012\",\"displayName\":\"User\"}"),
                Arguments.of("/auth/register", "{\"email\":\"test@example.com\",\"password\":\"short\",\"displayName\":\"User\"}"),
                Arguments.of("/auth/register", "{\"email\":\"test@example.com\",\"password\":\"" + "x".repeat(257) + "\",\"displayName\":\"User\"}"),
                Arguments.of("/auth/register", "{\"email\":\"test@example.com\",\"password\":123456789012,\"displayName\":\"User\"}"),
                Arguments.of("/auth/register", "{\"email\":\"test@example.com\",\"password\":\"123456789012\",\"displayName\":\"User\",\"role\":\"ADMIN\"}"),
                Arguments.of("/auth/login", "{}"),
                Arguments.of("/auth/login", "{\"email\":\"test@example.com\",\"password\":\"\"}"),
                Arguments.of("/auth/login", "{\"email\":\"test@example.com\",\"password\":null}"),
                Arguments.of("/auth/login", "{\"email\":\"test@example.com\",\"password\":\"short\",\"extra\":true}"),
                Arguments.of("/auth/login", "{\"email\":\"test@example.com\",\"email\":\"second@example.com\",\"password\":\"short\"}"),
                Arguments.of("/auth/login", "{bad json}")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidAuthBodies")
    void malformedAuthInputsProduceContractValidationErrors(String path, String payload) throws Exception {
        var problem = call("POST", path, null, payload, 400);
        assertThat(problem.path("code").stringValue()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void unsupportedMediaAndOversizedBodiesCannotReachPasswordHashing() throws Exception {
        request("POST", "/auth/login", null, loginPayload(email, PASSWORD), 400,
                Map.of("Content-Type", "text/plain"));
        var excessive = json.writeValueAsString(Map.of("email", email, "password", "X".repeat(100_000)));
        call("POST", "/auth/login", null, excessive, 400);
        call("GET", "/me", token, null, 200);
    }

    @Test
    void twoUsersHaveIndependentProfilesSavedEventsAndApplicationMarks() throws Exception {
        var other = register("second@example.com", PASSWORD, "Другой пользователь");
        var otherToken = other.path("accessToken").stringValue();
        call("PATCH", "/me", token, "{\"city\":\"Астана\",\"interests\":[\"java\"]}", 200);
        call("PUT", "/me/saved/" + AI, token, null, 204);
        call("PUT", "/me/applications/" + FINTECH, token, null, 200);
        assertThat(call("GET", "/me", otherToken, null, 200).path("city").isNull()).isTrue();
        assertStats(call("GET", "/me", otherToken, null, 200), 0, 0, 0);
        assertThat(call("GET", "/me/saved", otherToken, null, 200).path("items").isEmpty()).isTrue();
        assertThat(call("GET", "/me/applications", otherToken, null, 200).path("items").isEmpty()).isTrue();
        call("DELETE", "/me/saved/" + AI, otherToken, null, 204);
        call("DELETE", "/me/applications/" + FINTECH, otherToken, null, 204);
        assertStats(call("GET", "/me", token, null, 200), 1, 1, 1);
        call("PUT", "/me/saved/" + GAME, otherToken, null, 204);
        assertThat(savedIds(token)).containsExactly(AI);
        assertThat(savedIds(otherToken)).containsExactly(GAME);
    }

    @Test
    void guestImportCanRetryAfterPartialSuccessAndBookmarkAndApplicationAreIndependent() throws Exception {
        call("PUT", "/me/saved/" + AI, token, null, 204);
        call("PUT", "/me/saved/" + UNKNOWN, token, null, 404);
        call("PUT", "/me/saved/" + GAME, token, null, 204);
        call("PUT", "/me/saved/" + AI, token, null, 204);
        assertThat(savedIds(token)).containsExactly(AI, GAME);
        var mark = call("PUT", "/me/applications/" + AI, token, null, 200);
        assertThat(mark.path("hackathonId").stringValue()).isEqualTo(AI);
        assertThat(mark.path("status").stringValue()).isEqualTo("APPLIED");
        assertThat(mark.path("selfReported").booleanValue()).isTrue();
        call("DELETE", "/me/saved/" + AI, token, null, 204);
        call("DELETE", "/me/saved/" + AI, token, null, 204);
        assertThat(applicationIds(token)).containsExactly(AI);
        call("PUT", "/me/saved/" + AI, token, null, 204);
        call("DELETE", "/me/applications/" + AI, token, null, 204);
        call("DELETE", "/me/applications/" + AI, token, null, 204);
        assertThat(savedIds(token)).containsExactly(AI, GAME);
        assertThat(applicationIds(token)).isEmpty();
        call("DELETE", "/me/saved/" + UNKNOWN, token, null, 204);
        call("DELETE", "/me/applications/" + UNKNOWN, token, null, 204);
    }

    @Test
    void repeatedPutsPreserveOriginalTimestampsAndOrder() throws Exception {
        call("PUT", "/me/saved/" + AI, token, null, 204);
        call("PUT", "/me/applications/" + AI, token, null, 200);
        var earlier = IntegrationTestConfiguration.NOW.minusSeconds(60);
        jdbc.update("update hackatlas.saved_hackathon set saved_at = ? where user_id = ?", Timestamp.from(earlier), userId);
        jdbc.update("update hackatlas.application_mark set marked_at = ? where user_id = ?", Timestamp.from(earlier), userId);
        call("PUT", "/me/saved/" + GAME, token, null, 204);
        call("PUT", "/me/applications/" + GAME, token, null, 200);
        call("PUT", "/me/saved/" + AI, token, null, 204);
        var repeated = call("PUT", "/me/applications/" + AI, token, null, 200);
        assertThat(repeated.path("markedAt").stringValue()).isEqualTo(earlier.toString());
        assertThat(savedIds(token)).containsExactly(GAME, AI);
        assertThat(applicationIds(token)).containsExactly(GAME, AI);
    }

    @Test
    void publishedEndedAndCancelledEventsRemainSavedWhileDraftAndArchivedAreHidden() throws Exception {
        for (var event : List.of(AI, FINTECH, GAME, GREEN)) {
            call("PUT", "/me/saved/" + event, token, null, 204);
            call("PUT", "/me/applications/" + event, token, null, 200);
        }
        jdbc.update("update hackatlas.catalog_hackathon set event_status = 'ENDED' where id = ?", UUID.fromString(AI));
        jdbc.update("update hackatlas.catalog_hackathon set event_status = 'CANCELLED' where id = ?", UUID.fromString(GAME));
        jdbc.update("update hackatlas.catalog_hackathon set publication_status = 'DRAFT' where id = ?", UUID.fromString(FINTECH));
        jdbc.update("update hackatlas.catalog_hackathon set publication_status = 'ARCHIVED' where id = ?", UUID.fromString(GREEN));
        var saved = call("GET", "/me/saved", token, null, 200);
        assertThat(values(saved.path("items"), "id")).containsExactly(AI, GAME);
        assertThat(values(saved.path("items"), "eventStatus")).containsExactly("ENDED", "CANCELLED");
        assertThat(applicationIds(token)).containsExactly(AI, GAME);
        assertStats(call("GET", "/me", token, null, 200), 2, 2, 0);
        call("PUT", "/me/saved/" + FINTECH, token, null, 404);
        call("PUT", "/me/applications/" + GREEN, token, null, 404);
        // Hidden events can still be removed without leaking their publication state.
        call("DELETE", "/me/saved/" + FINTECH, token, null, 204);
        call("DELETE", "/me/applications/" + GREEN, token, null, 204);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/me/saved", "/me/applications"})
    void personalListsHaveStablePagingAndNoArtificialIntegerPageLimit(String path) throws Exception {
        for (var event : List.of(AI, FINTECH, GAME)) {
            call("PUT", path + "/" + event, token, null, path.endsWith("saved") ? 204 : 200);
        }
        var first = call("GET", path + "?size=2", token, null, 200);
        assertThat(first.path("totalElements").intValue()).isEqualTo(3);
        assertThat(first.path("totalPages").intValue()).isEqualTo(2);
        assertThat(first.path("hasNext").booleanValue()).isTrue();
        assertThat(first.path("items").size()).isEqualTo(2);
        var last = call("GET", path + "?page=1&size=2", token, null, 200);
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("hasNext").booleanValue()).isFalse();
        var hugePage = new BigInteger("123456789012345678901234567890");
        var beyond = call("GET", path + "?page=" + hugePage, token, null, 200);
        assertThat(beyond.path("page").bigIntegerValue()).isEqualTo(hugePage);
        assertThat(beyond.path("items").isEmpty()).isTrue();
        assertThat(beyond.path("totalElements").intValue()).isEqualTo(3);
        assertThat(beyond.path("asOf").stringValue()).isEqualTo(IntegrationTestConfiguration.NOW.toString());
    }

    static Stream<Arguments> invalidPersonalQueries() {
        return Stream.of("/me/saved", "/me/applications").flatMap(path -> Stream.of(
                "?page=-1", "?page=1.5", "?size=0", "?size=51", "?size=abc", "?page=0&page=1",
                "?userId=" + UNKNOWN, "?sort=NEWEST").map(query -> Arguments.of(path + query)));
    }

    @ParameterizedTest
    @MethodSource("invalidPersonalQueries")
    void personalListsValidatePaginationAndRejectUserSelectors(String path) throws Exception {
        call("GET", path, token, null, 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/me/saved/not-a-uuid", "/me/applications/not-a-uuid"})
    void personalMutationsValidateIds(String path) throws Exception {
        call("PUT", path, token, null, 400);
        call("DELETE", path, token, null, 400);
    }

    @Test
    void concurrentRepeatedPersonalWritesProduceOnlyOneRecord() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = Stream.generate(() -> (java.util.concurrent.Callable<Void>) () -> {
                call("PUT", "/me/saved/" + AI, token, null, 204);
                call("PUT", "/me/applications/" + AI, token, null, 200);
                return null;
            }).limit(6).toList();
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        assertStats(call("GET", "/me", token, null, 200), 1, 1, 0);
        assertThat(savedIds(token)).containsExactly(AI);
        assertThat(applicationIds(token)).containsExactly(AI);
    }

    @Test
    void concurrentPartialProfilePatchesPreserveBothIndependentChanges() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var name = executor.submit(() -> call("PATCH", "/me", token,
                    "{\"displayName\":\"Concurrent name\",\"interests\":[\"kotlin\"]}", 200));
            var city = executor.submit(() -> call("PATCH", "/me", token,
                    "{\"city\":\"Астана\",\"countryCode\":\"KZ\"}", 200));
            name.get();
            city.get();
        }
        var profile = call("GET", "/me", token, null, 200);
        assertThat(profile.path("displayName").stringValue()).isEqualTo("Concurrent name");
        assertThat(profile.path("city").stringValue()).isEqualTo("Астана");
        assertThat(profile.path("countryCode").stringValue()).isEqualTo("KZ");
        assertThat(profile.path("interests").get(0).stringValue()).isEqualTo("kotlin");
    }

    private List<String> savedIds(String bearer) throws Exception {
        return values(call("GET", "/me/saved", bearer, null, 200).path("items"), "id");
    }

    private List<String> applicationIds(String bearer) throws Exception {
        return values(call("GET", "/me/applications", bearer, null, 200).path("items"), "hackathonId");
    }

    private static String tokenHash(String bearer) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bearer.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertStats(JsonNode profile, int saved, int applied, int interests) {
        assertThat(profile.path("stats").path("savedCount").intValue()).isEqualTo(saved);
        assertThat(profile.path("stats").path("appliedCount").intValue()).isEqualTo(applied);
        assertThat(profile.path("stats").path("interestsCount").intValue()).isEqualTo(interests);
    }
}
