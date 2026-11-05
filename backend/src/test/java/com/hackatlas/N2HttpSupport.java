package com.hackatlas;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.hackatlas.catalog.CatalogDemoSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=10000",
                "hackatlas.auth.rate-limit.account-limit=10000", "hackatlas.lifecycle.request-ip-limit=10000",
                "hackatlas.lifecycle.request-email-limit=10000", "hackatlas.lifecycle.verify-ip-limit=10000",
                "server.servlet.session.cookie.secure=false"})
@ActiveProfiles({"test", "lifecycle-test"})
@Import(LifecycleIntegrationConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class N2HttpSupport extends B2HttpSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogDemoSeeder seeder;
    String email;
    String delivery;
    String token;
    UUID user;

    @DynamicPropertySource
    static void closedConfiguration(DynamicPropertyRegistry properties) {
        properties.add("hackatlas.lifecycle.key-file", () -> LifecycleIntegrationConfiguration.KEY.toString());
        properties.add("hackatlas.lifecycle.smtp-port", () -> LifecycleIntegrationConfiguration.MAIL.getMappedPort(1025));
    }

    @Override String contractPhase() { return "N2"; }

    @BeforeEach
    void freshN2Accounts() throws Exception {
        LifecycleIntegrationConfiguration.CLOCK.reset();
        jdbc.execute("TRUNCATE hackatlas.user_account,hackatlas.catalog_hackathon,hackatlas.catalog_tag,hackatlas.account_deletion_receipt CASCADE");
        seeder.seed();
        delivery = "Owner." + UUID.randomUUID() + "@Example.COM";
        email = delivery.toLowerCase(java.util.Locale.ROOT);
        var auth = register(delivery, PASSWORD, "Closed lifecycle test");
        token = auth.path("accessToken").stringValue();
        user = UUID.fromString(auth.path("user").path("id").stringValue());
    }

    JsonNode verificationRequest() throws Exception {
        var response = request("POST", "/auth/email-verification/request", token, null, 202);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return body(response);
    }

    JsonNode resetRequest(String address, String attachedToken) throws Exception {
        var response = request("POST", "/auth/password-reset/request", attachedToken,
                json.writeValueAsString(Map.of("email", address)), 202);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return body(response);
    }

    String code(String address, JsonNode challenge) throws Exception {
        String label = challenge.path("challengeId").stringValue().substring(37);
        final String[] text = new String[1];
        await().atMost(12, TimeUnit.SECONDS).until(() -> {
            var response = mailbox("GET", "/view/latest.txt?query=" + URLEncoder.encode("to:" + address, StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || !response.body().contains(label)) return false;
            text[0] = response.body();
            return true;
        });
        var match = Pattern.compile("Код: ([0-9]{8})").matcher(text[0]);
        assertThat(match.find()).as("SMTP message contains one 8-digit code").isTrue();
        return match.group(1);
    }

    String codePayload(JsonNode challenge, String code) {
        return json.writeValueAsString(Map.of("challengeId", challenge.path("challengeId").stringValue(), "code", code));
    }

    void verified() throws Exception {
        var challenge = verificationRequest();
        call("POST", "/auth/email-verification/confirm", token, codePayload(challenge, code(email, challenge)), 204);
    }

    HttpResponse<String> mailbox(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + LifecycleIntegrationConfiguration.MAIL.getMappedPort(8025) + path))
                    .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
