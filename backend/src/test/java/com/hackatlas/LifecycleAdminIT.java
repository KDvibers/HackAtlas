package com.hackatlas;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LifecycleAdminIT extends N2HttpSupport {
    @Autowired PlatformTransactionManager transactions;

    @Test
    void anAdminMembershipDoesNotLetPendingAccountLoginUntilEmailIsVerified() throws Exception {
        jdbc.update("INSERT INTO hackatlas.admin_access(user_id) VALUES(?)", user);
        try (var browser = new Browser()) {
            assertThat(browser.login(PASSWORD).headers().firstValue("Location").orElse("")).endsWith("/admin/login?error");
            verified();
            assertThat(browser.login(PASSWORD).headers().firstValue("Location").orElse("")).endsWith("/admin");
            assertThat(browser.get("/admin").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void passwordResetInvalidatesExistingBrowserCookieAndOldAdminPassword() throws Exception {
        verified();
        jdbc.update("INSERT INTO hackatlas.admin_access(user_id) VALUES(?)", user);
        try (var browser = new Browser()) {
            browser.login(PASSWORD);
            assertThat(browser.get("/admin").statusCode()).isEqualTo(200);
            LifecycleIntegrationConfiguration.CLOCK.advance(Duration.ofSeconds(60));
            var challenge = resetRequest(email, null);
            var grant = call("POST", "/auth/password-reset/verify", null, codePayload(challenge, code(email, challenge)), 200);
            call("POST", "/auth/password-reset/confirm", null, json.writeValueAsString(Map.of(
                    "resetToken", grant.path("resetToken").stringValue(), "newPassword", "new-admin-synthetic-passphrase")), 204);
            assertThat(browser.get("/admin").statusCode()).isEqualTo(403);
            assertThat(browser.login(PASSWORD).headers().firstValue("Location").orElse("")).endsWith("/admin/login?error");
            assertThat(browser.login("new-admin-synthetic-passphrase").headers().firstValue("Location").orElse("")).endsWith("/admin");
            assertThat(browser.get("/admin").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void anInFlightAdminMutationRechecksCredentialVersionAfterWaitingForItsAccountLock() throws Exception {
        verified();
        jdbc.update("INSERT INTO hackatlas.admin_access(user_id) VALUES(?)", user);
        try (var browser = new Browser(); var executor = Executors.newSingleThreadExecutor()) {
            browser.login(PASSWORD);
            String csrf = Browser.csrf(browser.get("/admin/events/" + AI + "/edit").body());
            var result = new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM hackatlas.user_account WHERE id=? FOR UPDATE", UUID.class, user);
                var pending = executor.submit(() -> browser.post("/admin/events/" + AI + "/archive", Map.of("_csrf", csrf)));
                await().atMost(8, TimeUnit.SECONDS).until(() -> jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                        AND wait_event_type='Lock' AND query LIKE '%FOR SHARE%')
                        """, Boolean.class));
                jdbc.update("UPDATE hackatlas.user_account SET credential_version=credential_version+1 WHERE id=?", user);
                return pending;
            });
            assertThat(result.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(403);
            assertThat(jdbc.queryForObject("SELECT publication_status FROM hackatlas.catalog_hackathon WHERE id=?", String.class,
                    UUID.fromString(AI))).isEqualTo("PUBLISHED");
        }
    }

    private final class Browser implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null); }
        HttpResponse<String> post(String path, Map<String,String> values) throws Exception {
            return send("POST", path, values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&")));
        }
        HttpResponse<String> login(String password) throws Exception {
            var page = get("/admin/login");
            assertThat(page.statusCode()).isEqualTo(200);
            return post("/admin/login", Map.of("username", email, "password", password, "_csrf", csrf(page.body())));
        }
        private HttpResponse<String> send(String method, String path, String data) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort() + path)).timeout(Duration.ofSeconds(20));
            if (data != null) builder.header("Content-Type", "application/x-www-form-urlencoded");
            return client.send(builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
        }
        static String csrf(String html) {
            var match = Pattern.compile("<input(?=[^>]*name=\"_csrf\")(?=[^>]*value=\"([^\"]+)\")[^>]*>").matcher(html);
            assertThat(match.find()).as("admin form contains CSRF token").isTrue();
            return match.group(1);
        }
        @Override public void close() { client.close(); }
    }
}
