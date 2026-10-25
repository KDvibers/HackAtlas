package com.hackatlas;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.hackatlas.catalog.CatalogDemoSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=10000",
                "hackatlas.auth.rate-limit.account-limit=10000", "hackatlas.moderation.rate-limit.ip-limit=10000",
                "hackatlas.moderation.rate-limit.account-limit=10000", "server.servlet.session.cookie.secure=false"})
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminWebIT extends B2HttpSupport {
    private static final Pattern CSRF_INPUT = Pattern.compile("<input(?=[^>]*name=\"_csrf\")(?=[^>]*value=\"([^\"]+)\")[^>]*>");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CatalogDemoSeeder seeder;
    private String adminEmail;
    private String adminToken;
    private UUID adminId;

    @Override String contractPhase() { return "B3"; }

    @BeforeEach
    void createOperatorGrantedAdmin() throws Exception {
        jdbc.execute("truncate table hackatlas.user_account, hackatlas.catalog_hackathon, hackatlas.catalog_tag cascade");
        seeder.seed();
        adminEmail = "operator-" + UUID.randomUUID() + "@example.com";
        var account = register(adminEmail, PASSWORD, "Редактор");
        adminId = UUID.fromString(account.path("user").path("id").stringValue());
        adminToken = account.path("accessToken").stringValue();
        jdbc.update("insert into hackatlas.admin_access(user_id) values(?)", adminId);
    }

    @Test
    void bearerAndRegularUserCredentialsCannotEnterAdminAndBrowserCookieCannotAuthenticateApi() throws Exception {
        var user = register("ordinary@example.com", PASSWORD, "Обычный пользователь");
        try (var browser = new Browser()) {
            assertLoginRedirect(browser.get("/admin", 302));
            assertLoginRedirect(browser.send("GET", "/admin", null, 302,
                    Map.of("Authorization", "Bearer " + adminToken)));
            var login = browser.get("/admin/login", 200);
            var denied = browser.post("/admin/login", Map.of("username", "ordinary@example.com", "password", PASSWORD,
                    "_csrf", csrf(login.body())), 302);
            assertThat(denied.headers().firstValue("Location").orElse("")).endsWith("/admin/login?error");
            assertLoginRedirect(browser.get("/admin", 302));
            login(browser);
            var profile = browser.send("GET", "/api/v1/me", null, 401,
                    Map.of("Cookie", browser.sessionCookieHeader()));
            assertThat(body(profile).path("code").stringValue()).isEqualTo("AUTH_REQUIRED");
            var suggestion = browser.send("POST", "/api/v1/suggestions", "{\"url\":\"https://example.com/event\",\"comment\":null}",
                    401, Map.of("Content-Type", "application/json", "Cookie", browser.sessionCookieHeader()));
            assertThat(body(suggestion).path("code").stringValue()).isEqualTo("AUTH_REQUIRED");
        }
        // Also capture the API boundary in the canonical B3 export.
        call("POST", "/suggestions", null, "{}", 401);
        call("GET", "/me", user.path("accessToken").stringValue(), null, 200);
    }

    @Test
    void loginAndEveryMutationRequireCsrfAndSessionCookieIsHttpOnlySameSite() throws Exception {
        try (var browser = new Browser()) {
            var loginPage = browser.get("/admin/login", 200);
            assertThat(loginPage.headers().allValues("Set-Cookie")).anySatisfy(cookie -> {
                assertThat(cookie.toLowerCase()).contains("httponly", "samesite=strict", "path=/admin");
            });
            browser.post("/admin/login", Map.of("username", adminEmail, "password", PASSWORD), 403);
            login(browser);
            browser.post("/admin/events/" + AI + "/archive", Map.of(), 403);
            assertThat(jdbc.queryForObject("select publication_status from hackatlas.catalog_hackathon where id=?", String.class,
                    UUID.fromString(AI))).isEqualTo("PUBLISHED");
            browser.post("/admin/events/" + AI + "/archive", Map.of("_csrf", "invalid"), 403);
            var csrf = csrf(browser.get("/admin/events/" + AI + "/edit", 200).body());
            browser.post("/admin/events/" + AI + "/archive", Map.of("_csrf", csrf), 302);
            assertThat(jdbc.queryForObject("select publication_status from hackatlas.catalog_hackathon where id=?", String.class,
                    UUID.fromString(AI))).isEqualTo("ARCHIVED");
        }
    }

    @Test
    void operatorCanCreateOnlyDraftThenExplicitlyVerifyPublishAndArchive() throws Exception {
        try (var browser = authenticatedBrowser()) {
            var fields = eventForm("Проверенный новый хакатон");
            fields.put("_csrf", csrf(browser.get("/admin/events/new", 200).body()));
            browser.post("/admin/events", fields, 302);
            var id = jdbc.queryForObject("select id from hackatlas.catalog_hackathon where title=?", UUID.class, fields.get("title"));
            assertThat(jdbc.queryForObject("select publication_status from hackatlas.catalog_hackathon where id=?", String.class, id)).isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject("select last_verified_at from hackatlas.catalog_hackathon where id=?", Timestamp.class, id)).isNull();
            var editPath = "/admin/events/" + id;
            var csrf = csrf(browser.get(editPath + "/edit", 200).body());
            browser.post(editPath + "/publish", Map.of("_csrf", csrf, "reviewConfirmed", "true"), 400);
            browser.post(editPath + "/verify", Map.of("_csrf", csrf), 400);
            browser.post(editPath + "/verify", Map.of("_csrf", csrf, "sourceChecked", "true"), 302);
            browser.post(editPath + "/publish", Map.of("_csrf", csrf), 400);
            browser.post(editPath + "/publish", Map.of("_csrf", csrf, "reviewConfirmed", "true"), 302);
            var detail = call("GET", "/hackathons/" + id, null, null, 200);
            assertThat(detail.path("title").stringValue()).isEqualTo(fields.get("title"));
            assertThat(detail.path("prize").path("status").stringValue()).isEqualTo("UNKNOWN");
            assertThat(detail.path("prize").path("totalAmount").isNull()).isTrue();
            assertThat(detail.path("lastVerifiedAt").stringValue()).isEqualTo(IntegrationTestConfiguration.NOW.toString());
            assertThat(detail.path("starts").path("precision").stringValue()).isEqualTo("DATE");
            assertThat(detail.path("starts").path("instant").isNull()).isTrue();
            assertThat(detail.path("starts").path("time").isNull()).isTrue();
            call("PUT", "/me/saved/" + id, adminToken, null, 204);
            browser.post(editPath + "/archive", Map.of("_csrf", csrf), 302);
            assertThat(call("GET", "/me/saved", adminToken, null, 200).path("items").isEmpty()).isTrue();
            call("GET", "/hackathons/" + id, null, null, 404);
        }
    }

    @Test
    void editingCancellationAndDatesUpdatesSavedWithoutPretendingTheSourceWasReverified() throws Exception {
        var earlier = IntegrationTestConfiguration.NOW.minus(Duration.ofDays(2));
        jdbc.update("update hackatlas.catalog_hackathon set last_verified_at=?,updated_at=? where id=?",
                Timestamp.from(earlier), Timestamp.from(earlier), UUID.fromString(AI));
        call("PUT", "/me/saved/" + AI, adminToken, null, 204);
        try (var browser = authenticatedBrowser()) {
            var fields = eventForm("Событие отменено организатором");
            fields.put("sourceUrl", jdbc.queryForObject("select source_url from hackatlas.catalog_hackathon where id=?", String.class, UUID.fromString(AI)));
            fields.put("officialUrl", jdbc.queryForObject("select official_url from hackatlas.catalog_hackathon where id=?", String.class, UUID.fromString(AI)));
            fields.put("eventStatus", "CANCELLED");
            fields.put("_csrf", csrf(browser.get("/admin/events/" + AI + "/edit", 200).body()));
            browser.post("/admin/events/" + AI, fields, 302);
            var saved = call("GET", "/me/saved", adminToken, null, 200).path("items").get(0);
            assertThat(saved.path("id").stringValue()).isEqualTo(AI);
            assertThat(saved.path("title").stringValue()).isEqualTo(fields.get("title"));
            assertThat(saved.path("eventStatus").stringValue()).isEqualTo("CANCELLED");
            assertThat(saved.path("starts").path("precision").stringValue()).isEqualTo("DATE");
            assertThat(saved.path("starts").path("instant").isNull()).isTrue();
            assertThat(saved.path("updatedAt").stringValue()).isEqualTo(IntegrationTestConfiguration.NOW.toString());
            var verified = jdbc.queryForObject("select last_verified_at from hackatlas.catalog_hackathon where id=?", Timestamp.class,
                    UUID.fromString(AI));
            assertThat(verified.toInstant()).isEqualTo(earlier);
            browser.post("/admin/events/" + AI + "/verify", Map.of("_csrf", fields.get("_csrf"), "sourceChecked", "true"), 302);
            assertThat(jdbc.queryForObject("select last_verified_at from hackatlas.catalog_hackathon where id=?", Timestamp.class,
                    UUID.fromString(AI)).toInstant()).isEqualTo(IntegrationTestConfiguration.NOW);
            fields.put("sourceUrl", "https://example.com/replaced-source");
            browser.post("/admin/events/" + AI, fields, 302);
            assertThat(jdbc.queryForObject("select last_verified_at from hackatlas.catalog_hackathon where id=?", Timestamp.class,
                    UUID.fromString(AI))).isNull();
        }
    }

    @Test
    void invalidDateTimesAndUnsafeUrlsDoNotPartiallyModifyEvent() throws Exception {
        try (var browser = authenticatedBrowser()) {
            var fields = eventForm("Must not be persisted");
            fields.put("_csrf", csrf(browser.get("/admin/events/" + AI + "/edit", 200).body()));
            fields.put("startsPrecision", "MINUTE");
            fields.put("startsDate", "2026-03-29");
            fields.put("startsTime", "02:30");
            fields.put("startsTimeZone", "Europe/Berlin");
            browser.post("/admin/events/" + AI, fields, 400);
            fields.put("startsDate", "2026-10-25");
            browser.post("/admin/events/" + AI, fields, 400);
            fields.put("startsTimeZone", "Invalid/Timezone");
            browser.post("/admin/events/" + AI, fields, 400);
            fields = eventForm("Must not be persisted");
            fields.put("_csrf", csrf(browser.get("/admin/events/" + AI + "/edit", 200).body()));
            fields.put("sourceUrl", "https://admin:secret@example.com/event");
            browser.post("/admin/events/" + AI, fields, 400);
            assertThat(jdbc.queryForObject("select title from hackatlas.catalog_hackathon where id=?", String.class,
                    UUID.fromString(AI))).isEqualTo("AI Sprint 2026");
        }
    }

    @Test
    void queueEscapesUntrustedTextAndReviewDoesNotPublishSuggestedEvent() throws Exception {
        var unsafeText = "<script>alert('untrusted')</script>";
        var submission = call("POST", "/suggestions", adminToken, json.writeValueAsString(Map.of(
                "url", "https://example.com/pending", "comment", unsafeText)), 202);
        var id = UUID.fromString(submission.path("id").stringValue());
        try (var browser = authenticatedBrowser()) {
            var queue = browser.get("/admin/queue", 200);
            assertThat(queue.body()).contains("&lt;script&gt;").doesNotContain(unsafeText);
            browser.post("/admin/queue/" + id + "/review", Map.of("decision", "RESOLVED"), 403);
            browser.post("/admin/queue/" + id + "/review", Map.of("decision", "PUBLISHED", "_csrf", csrf(queue.body())), 400);
            browser.post("/admin/queue/" + id + "/review", Map.of("decision", "RESOLVED", "_csrf", csrf(queue.body())), 302);
            assertThat(jdbc.queryForObject("select status from hackatlas.moderation_submission where id=?", String.class, id)).isEqualTo("RESOLVED");
            assertThat(jdbc.queryForObject("select reviewed_at from hackatlas.moderation_submission where id=?", Timestamp.class, id).toInstant())
                    .isEqualTo(IntegrationTestConfiguration.NOW);
            assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_hackathon", Integer.class)).isEqualTo(5);
        }
    }

    @Test
    void revokedPrivilegeAndDeletedAccountInvalidateAlreadyAuthenticatedBrowsers() throws Exception {
        try (var revoked = authenticatedBrowser()) {
            jdbc.update("delete from hackatlas.admin_access where user_id=?", adminId);
            revoked.get("/admin", 403);
            assertLoginRedirect(revoked.get("/admin", 302));
        }
        jdbc.update("insert into hackatlas.admin_access(user_id) values(?)", adminId);
        try (var deleted = authenticatedBrowser()) {
            call("POST", "/me/delete-account", adminToken, json.writeValueAsString(Map.of("password", PASSWORD)), 204);
            deleted.get("/admin", 403);
            assertLoginRedirect(deleted.get("/admin", 302));
        }
    }

    @Test
    void logoutRequiresCsrfAndEndsOnlyTheBrowserSession() throws Exception {
        try (var browser = authenticatedBrowser()) {
            var dashboard = browser.get("/admin", 200);
            browser.post("/admin/logout", Map.of(), 403);
            browser.post("/admin/logout", Map.of("_csrf", csrf(dashboard.body())), 302);
            assertLoginRedirect(browser.get("/admin", 302));
            call("GET", "/me", adminToken, null, 200);
        }
    }

    private Browser authenticatedBrowser() throws Exception {
        var browser = new Browser();
        login(browser);
        return browser;
    }

    private void login(Browser browser) throws Exception {
        var page = browser.get("/admin/login", 200);
        var response = browser.post("/admin/login", Map.of("username", adminEmail, "password", PASSWORD,
                "_csrf", csrf(page.body())), 302);
        assertThat(response.headers().firstValue("Location").orElse("")).endsWith("/admin");
        browser.get("/admin", 200);
    }

    private static String csrf(String html) {
        var matcher = CSRF_INPUT.matcher(html);
        assertThat(matcher.find()).as("rendered page must contain a CSRF hidden input").isTrue();
        return matcher.group(1);
    }

    private static void assertLoginRedirect(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Location").orElse("")).endsWith("/admin/login");
    }

    private static Map<String, String> eventForm(String title) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("title", title);
        fields.put("shortDescription", "Проверяемое описание события");
        fields.put("description", "Подробности, которые редактор проверил по официальному источнику.");
        fields.put("organizerName", "Проверяемый организатор");
        fields.put("organizerWebsiteUrl", "https://example.com/organizer");
        fields.put("format", "ONLINE");
        fields.put("countryCode", ""); fields.put("city", ""); fields.put("venue", "");
        fields.put("officialUrl", "https://example.com/official");
        fields.put("registrationUrl", "https://example.com/register");
        fields.put("rulesUrl", ""); fields.put("sourceUrl", "https://example.com/source");
        fields.put("registrationStatus", "OPEN"); fields.put("eventStatus", "SCHEDULED");
        for (String prefix : new String[]{"starts", "ends", "deadline"}) {
            fields.put(prefix + "Precision", "DATE");
            fields.put(prefix + "Date", prefix.equals("starts") ? "2026-11-12" : prefix.equals("ends") ? "2026-11-14" : "2026-11-01");
            fields.put(prefix + "Time", ""); fields.put(prefix + "TimeZone", "Asia/Almaty");
        }
        return fields;
    }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> get(String path, int status) throws Exception { return send("GET", path, null, status, Map.of()); }
        String sessionCookieHeader() {
            return cookies.getCookieStore().getCookies().stream().map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; "));
        }
        HttpResponse<String> post(String path, Map<String, String> fields, int status) throws Exception {
            var data = fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(Collectors.joining("&"));
            return send("POST", path, data, status, Map.of("Content-Type", "application/x-www-form-urlencoded"));
        }
        HttpResponse<String> send(String method, String path, String content, int status, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort() + path)).timeout(Duration.ofSeconds(20));
            headers.forEach(request::header);
            request.method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s browser status", method, path).isEqualTo(status);
            return response;
        }
        @Override public void close() { client.close(); }
        private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    }
}
