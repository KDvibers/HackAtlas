package com.hackatlas;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfiguration {

    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @Bean
    @Primary
    Clock testClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresDatabase() {
        // Same 17.11-bookworm digest as Compose; Testcontainers expects name@digest without a tag.
        return new PostgreSQLContainer(DockerImageName.parse(
                "postgres@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5"
        ))
                .withDatabaseName("hackatlas_integration_test")
                .withUsername("hackatlas_test")
                .withPassword(UUID.randomUUID().toString())
                .withReuse(false);
    }
}
