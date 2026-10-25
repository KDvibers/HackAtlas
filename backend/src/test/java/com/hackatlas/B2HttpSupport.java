package com.hackatlas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

abstract class B2HttpSupport {
    static final String AI = "39287368-5259-5235-a1c4-25b4e1e8421e";
    static final String FINTECH = "9ee483ad-ff37-5dae-8da1-ae901ea7d27f";
    static final String GAME = "9756e7f3-5796-5d69-93cf-a6a6cb8ebd33";
    static final String GREEN = "fc6f0e31-af57-52ac-a736-3d0cce49b3fa";
    static final String UNKNOWN = "00000000-0000-4000-8000-000000000099";
    static final String PASSWORD = "  Test-only пароль 🔐 2026  ";
    private static final String REDACTED_TOKEN = "x".repeat(43);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<ObjectNode> responses = Collections.synchronizedList(new ArrayList<>());

    @Value("${local.server.port}")
    private int port;
    @Autowired
    JsonMapper json;

    String contractPhase() { return "B2"; }

    int httpPort() { return port; }

    @AfterAll
    void exportResponses() throws Exception {
        try {
            var output = Path.of("target/" + contractPhase().toLowerCase() + "-contract", getClass().getSimpleName() + ".json");
            Files.createDirectories(output.getParent());
            json.writeValue(output.toFile(), Map.of("source", contractPhase() + " HTTP", "responses", responses));
        } finally {
            http.close();
        }
    }

    HttpResponse<String> request(String method, String path, String token, String body, int expected)
            throws Exception {
        return request(method, path, token, body, expected, Map.of());
    }

    HttpResponse<String> request(String method, String path, String token, String body, int expected,
                                 Map<String, String> extraHeaders) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null && !extraHeaders.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        }
        extraHeaders.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // Never include the response/request body in assertion messages: auth responses contain tokens.
        assertThat(response.statusCode()).as("%s %s status", method, operationPath(path)).isEqualTo(expected);
        var requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        var capture = json.createObjectNode().put("operationPath", operationPath(path))
                .put("method", method.toLowerCase()).put("status", response.statusCode())
                .put("contentType", response.headers().firstValue("Content-Type").orElse(""))
                .put("cacheControl", response.headers().firstValue("Cache-Control").orElse(""))
                .put("retryAfter", response.headers().firstValue("Retry-After").orElse(""));
        if (expected == 204) {
            assertThat(response.body()).isEmpty();
            capture.putNull("body");
        } else {
            var actual = json.readTree(response.body());
            var exported = actual.deepCopy();
            if (exported instanceof ObjectNode object && object.has("accessToken")) {
                object.put("accessToken", REDACTED_TOKEN);
            }
            capture.set("body", exported);
            if (expected >= 400) {
                assertThat(response.headers().firstValue("Content-Type").orElse(""))
                        .startsWith("application/problem+json");
                assertThat(actual.path("status").intValue()).isEqualTo(expected);
                assertThat(actual.path("requestId").stringValue()).isEqualTo(requestId);
                assertThat(response.body()).doesNotContain("org.hibernate", "java.sql", "password_hash",
                        "token_hash", "Authorization", "stackTrace", PASSWORD);
            }
        }
        boolean b3 = path.startsWith("/suggestions") || path.startsWith("/me/delete-account")
                || path.startsWith("/hackathons/") && path.split("\\?", 2)[0].endsWith("/reports");
        if (b3 == contractPhase().equals("B3")) responses.add(capture);
        return response;
    }

    JsonNode body(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    JsonNode call(String method, String path, String token, String payload, int status) throws Exception {
        var response = request(method, path, token, payload, status);
        return status == 204 ? json.nullNode() : body(response);
    }

    JsonNode register(String email, String password, String displayName) throws Exception {
        var response = request("POST", "/auth/register", null, json.writeValueAsString(Map.of(
                "email", email, "password", password, "displayName", displayName)), 201);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).doesNotContain(password, "password_hash", "token_hash");
        return body(response);
    }

    String loginPayload(String email, String password) {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    static List<String> values(JsonNode array, String field) {
        var result = new ArrayList<String>();
        for (var item : array) {
            result.add(item.path(field).stringValue());
        }
        return result;
    }

    private static String operationPath(String path) {
        var base = path.split("\\?", 2)[0];
        if (base.startsWith("/me/saved/")) return "/me/saved/{id}";
        if (base.startsWith("/me/applications/")) return "/me/applications/{id}";
        if (base.startsWith("/hackathons/") && base.endsWith("/reports")) return "/hackathons/{id}/reports";
        return base;
    }
}
