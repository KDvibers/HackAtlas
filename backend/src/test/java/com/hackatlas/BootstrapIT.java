package com.hackatlas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = HackAtlasApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hackatlas.demo.enabled=false"
)
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
class BootstrapIT {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JsonMapper jsonMapper;

    @AfterAll
    static void closeHttpClient() {
        HTTP.close();
    }

    @Test
    void startsAgainstPostgres17AndAppliesAllMigrations() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }

        assertThat(jdbc.queryForObject(
                "select exists (select 1 from pg_namespace where nspname = 'hackatlas')",
                Boolean.class
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from public.flyway_schema_history where version = '1' and success",
                Integer.class
        )).isEqualTo(1);
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(2).allSatisfy(migration -> {
            assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS);
        });
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_hackathon", Integer.class)).isZero();
    }

    @Test
    void repeatedMigrationValidatesAndDoesNotExecuteAgain() {
        flyway.validate();

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/health?showDetails=true"
    })
    void healthExposesOnlyStatusAndPublicProbeNames(String path) throws Exception {
        var response = request("GET", path);
        var expectedBody = jsonMapper.createObjectNode().put("status", "UP");
        if (path.equals("/actuator/health") || path.equals("/actuator/health?showDetails=true")) {
            expectedBody.putArray("groups").add("liveness").add("readiness");
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(contentType -> assertThat(contentType).contains("json"));
        assertThat(jsonMapper.readTree(response.body())).isEqualTo(expectedBody);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator",
            "/actuator/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/mappings",
            "/actuator/metrics",
            "/actuator/loggers",
            "/actuator/heapdump",
            "/actuator/flyway"
    })
    void otherActuatorEndpointsAreNotExposed(String path) throws Exception {
        assertThat(request("GET", path).statusCode()).isEqualTo(404);
    }

    @Test
    void profileEndpointIsNotImplementedInB1() throws Exception {
        assertThat(request("GET", "/api/v1/me").statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/logout"
    })
    void authEndpointsAreNotImplementedInB1(String path) throws Exception {
        assertThat(request("POST", path).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

}
