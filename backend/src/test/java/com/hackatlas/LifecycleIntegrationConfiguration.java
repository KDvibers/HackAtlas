package com.hackatlas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.Duration;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class LifecycleIntegrationConfiguration {
    static final MutableClock CLOCK = new MutableClock();
    static final Path KEY = testKey();
    static final GenericContainer<?> MAIL = LifecycleContainers.mailpit();

    static { MAIL.start(); }

    @Bean @Primary Clock lifecycleClock() { return CLOCK; }
    @Bean(destroyMethod = "stop") GenericContainer<?> mailpit() { return MAIL; }
    @Bean DisposableBean deleteEphemeralKey() { return () -> Files.deleteIfExists(KEY); }

    @Bean @ServiceConnection
    PostgreSQLContainer postgresDatabase() {
        return LifecycleContainers.postgres("hackatlas_lifecycle_test");
    }

    private static Path testKey() {
        try {
            Path file = Files.createTempFile("hackatlas-n2-test-", ".key");
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            Files.write(file, bytes);
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            return file;
        } catch (java.io.IOException exception) { throw new IllegalStateException("Ephemeral test key unavailable"); }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-03T10:00:00Z");
        void reset() { now = Instant.parse("2026-11-03T10:00:00Z"); }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
