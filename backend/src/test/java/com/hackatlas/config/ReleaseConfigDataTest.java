package com.hackatlas.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseConfigDataTest {
    @TempDir
    Path temporary;

    @Test
    void fileSecretsBindSeparatelyAndReleaseDefaultsDisableRuntimeMigrations() throws IOException {
        var config = copyConfig();
        String runtimePassword = UUID.randomUUID().toString();
        String migratorPassword = UUID.randomUUID().toString();
        Files.writeString(temporary.resolve("secrets/HACKATLAS_DB_PASSWORD"),runtimePassword);
        Files.writeString(temporary.resolve("secrets/HACKATLAS_FLYWAY_PASSWORD"),migratorPassword);
        runner(config,"prod,release").withUserConfiguration(ReleaseConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo(runtimePassword);
            assertThat(environment.getProperty("spring.flyway.password")).isEqualTo(migratorPassword);
            assertThat(environment.getProperty("spring.flyway.user")).isEqualTo("hackatlas_migrator");
            assertThat(environment.getProperty("spring.flyway.enabled",Boolean.class)).isFalse();
            assertThat(environment.getProperty("server.tomcat.use-relative-redirects",Boolean.class)).isTrue();
            assertThat(environment.getProperty("hackatlas.test.dev-file-loaded")).isNull();
        });
        runner(config,"prod,release").withUserConfiguration(ReleaseConfiguration.class)
                .withPropertyValues("HACKATLAS_MIGRATION_ONLY=true", "spring.main.web-application-type=none",
                        "spring.flyway.enabled=true").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("hackatlas.release.migration-only",Boolean.class)).isTrue();
                    assertThat(context.getEnvironment().getProperty("spring.flyway.password")).isEqualTo(migratorPassword);
                });
    }

    @Test
    void mixedProdDevDoesNotLoadTheDevelopmentFileOrWeakenCookieConfiguration() throws IOException {
        var config = copyConfig();
        runner(config,"prod,dev").withPropertyValues("HACKATLAS_DB_PASSWORD=synthetic-value").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("hackatlas.test.dev-file-loaded")).isNull();
            assertThat(context.getEnvironment().getProperty("server.servlet.session.cookie.secure",Boolean.class)).isTrue();
        });
    }

    private ApplicationContextRunner runner(Path config,String profiles) {
        return new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=" + config.toUri(), "spring.profiles.active=" + profiles,
                        "HACKATLAS_DB_URL=jdbc:postgresql://postgres:5432/hackatlas", "HACKATLAS_DB_USERNAME=hackatlas_runtime",
                        "HACKATLAS_FLYWAY_USERNAME=hackatlas_migrator");
    }

    private Path copyConfig() throws IOException {
        Path config = Files.createDirectory(temporary.resolve("config"));
        Path secrets = Files.createDirectory(temporary.resolve("secrets"));
        Path dev = temporary.resolve("synthetic-dev.properties");
        Files.writeString(dev,"hackatlas.test.dev-file-loaded=true\n");
        for (String name : new String[] {"application.yaml","application-prod.yaml","application-release.yaml","application-dev.yaml"}) {
            String text = new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8)
                    // configtree takes a filesystem path, not a nested file: URI.
                    .replace("optional:configtree:/run/secrets/","optional:configtree:"
                            + secrets.toAbsolutePath().toString().replace('\\','/') + "/")
                    .replace("optional:file:../infra/.env[.properties]","optional:" + dev.toUri() + "[.properties]");
            Files.writeString(config.resolve(name),text);
        }
        return config;
    }
}
