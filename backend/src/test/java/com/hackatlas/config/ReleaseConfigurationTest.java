package com.hackatlas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class ReleaseConfigurationTest {
    @Test
    void runtimeAndNonWebMigrationAreSeparateAcceptedModes() {
        var runtime = runtime();
        assertThatCode(() -> ReleaseConfiguration.validate(runtime)).doesNotThrowAnyException();
        var migration = runtime().withProperty("hackatlas.release.migration-only","true")
                .withProperty("spring.flyway.enabled","true").withProperty("spring.main.web-application-type","none")
                .withProperty("spring.flyway.url","jdbc:postgresql://postgres:5432/hackatlas");
        assertThatCode(() -> ReleaseConfiguration.validate(migration)).doesNotThrowAnyException();
        migration.setProperty("spring.main.web-application-type","servlet");
        assertThatThrownBy(() -> ReleaseConfiguration.validate(migration)).isInstanceOf(IllegalStateException.class);
        runtime.setProperty("spring.flyway.enabled","true");
        assertThatThrownBy(() -> ReleaseConfiguration.validate(runtime)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseRejectsDevelopmentDataClockAndProxyTrustOverrides() {
        for (var setting : new String[][] {
                {"hackatlas.demo.enabled","true"}, {"hackatlas.demo.as-of","2026-10-12T10:00:00Z"},
                {"hackatlas.auth.registration-enabled","true"}, {"server.forward-headers-strategy","native"},
                {"server.tomcat.remoteip.protocol-header","X-Forwarded-Proto"},
                {"server.tomcat.remoteip.remote-ip-header","X-Forwarded-For"},
                {"server.tomcat.use-relative-redirects","false"}}) {
            var environment = runtime().withProperty(setting[0],setting[1]);
            assertThatThrownBy(() -> ReleaseConfiguration.validate(environment)).isInstanceOf(IllegalStateException.class);
        }
        for (String forbidden : new String[] {"dev","test"}) {
            var environment = runtime();
            environment.addActiveProfile(forbidden);
            assertThatThrownBy(() -> ReleaseConfiguration.validate(environment)).isInstanceOf(IllegalStateException.class);
        }
        var missingProd = runtime();
        missingProd.setActiveProfiles("release");
        assertThatThrownBy(() -> ReleaseConfiguration.validate(missingProd)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={
            "jdbc:postgresql://user:private-value@postgres/hackatlas",
            "jdbc:postgresql://postgres/hackatlas?password=private-value",
            "jdbc:postgresql://postgres/hackatlas?%70assword=private-value",
            "jdbc:postgresql://postgres/hackatlas?sslpassword=private-value",
            "jdbc:postgresql://postgres/hackatlas?user=private-value",
            "jdbc:postgresql://postgres/hackatlas#private-value",
            "private-value"
    })
    void rejectsCredentialBearingOrInvalidJdbcUrlsWithoutEchoingTheirValues(String url) {
        var failure = catchThrowable(() -> ReleaseConfiguration.validateDatabaseUrl(url));
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage()).doesNotContain("private-value",url);
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void allowsNonSecretPostgresTlsAndTimeoutOptions() {
        assertThatCode(() -> ReleaseConfiguration.validateDatabaseUrl(
                "jdbc:postgresql://postgres:5432/hackatlas?sslmode=verify-full&connectTimeout=5&sslrootcert=/run/secrets/root.crt"))
                .doesNotThrowAnyException();
    }

    private static MockEnvironment runtime() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url","jdbc:postgresql://postgres:5432/hackatlas")
                .withProperty("spring.flyway.enabled","false").withProperty("server.tomcat.use-relative-redirects","true")
                .withProperty("spring.main.web-application-type","servlet");
        environment.setActiveProfiles("prod","release");
        return environment;
    }
}
