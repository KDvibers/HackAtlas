package com.hackatlas.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Fail before creating the connection pool if the release/migration boundary is misconfigured. */
@Configuration(proxyBeanMethods = false)
@Profile("release")
class ReleaseConfiguration {
    @Bean
    static BeanFactoryPostProcessor releaseConfigurationGuard(Environment environment) {
        return beanFactory -> validate(environment);
    }

    static void validate(Environment environment) {
        Set<String> profiles = Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
        if (!profiles.contains("prod") || profiles.contains("dev") || profiles.contains("test")) {
            throw invalid("Release requires prod and must not include dev/test profiles.");
        }
        if (environment.getProperty("hackatlas.demo.enabled", Boolean.class, false)
                || !environment.getProperty("hackatlas.demo.as-of", "").isBlank()) {
            throw invalid("Release cannot enable DEMO data or a fixed clock.");
        }
        if (environment.getProperty("hackatlas.auth.registration-enabled", Boolean.class, false)) {
            throw invalid("Release registration must remain closed.");
        }
        if (!environment.getProperty("server.forward-headers-strategy", "none").equalsIgnoreCase("none")) {
            throw invalid("Release does not trust Forwarded headers; keep the strategy set to none.");
        }
        // Boot 4.1 also installs RemoteIpValve for an explicit header even when the strategy is NONE.
        if (!environment.getProperty("server.tomcat.remoteip.protocol-header", "").isBlank()
                || !environment.getProperty("server.tomcat.remoteip.remote-ip-header", "").isBlank()) {
            throw invalid("Release must not configure Tomcat remote IP or protocol headers.");
        }
        if (!environment.getProperty("server.tomcat.use-relative-redirects", Boolean.class, false)) {
            throw invalid("Release requires relative redirects behind the HTTPS proxy.");
        }
        boolean migration = environment.getProperty("hackatlas.release.migration-only", Boolean.class, false);
        boolean flyway = environment.getProperty("spring.flyway.enabled", Boolean.class, false);
        String webType = environment.getProperty("spring.main.web-application-type", "servlet");
        if (migration != flyway || (migration ? !webType.equalsIgnoreCase("none") : !webType.equalsIgnoreCase("servlet"))) {
            throw invalid("Runtime requires servlet/Flyway disabled; migration-only requires non-web/Flyway enabled.");
        }
        validateDatabaseUrl(environment.getProperty("spring.datasource.url"));
        if (migration) { validateDatabaseUrl(environment.getProperty("spring.flyway.url")); }
    }

    static void validateDatabaseUrl(String value) {
        try {
            if (value == null || !value.startsWith("jdbc:postgresql://")) { throw new IllegalArgumentException(); }
            URI uri = URI.create(value.substring("jdbc:".length())).parseServerAuthority();
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            if (uri.getRawQuery() != null) {
                for (String item : uri.getRawQuery().split("&")) {
                    String name = URLDecoder.decode(item.split("=",2)[0],StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (Set.of("user","password","sslpassword").contains(name)) { throw new IllegalArgumentException(); }
                }
            }
        } catch (Exception exception) {
            // URI/parser exception text may include a credential-bearing URL. Never retain its cause.
            throw invalid("Release requires a PostgreSQL JDBC URL without embedded credentials; use separate secret files.");
        }
    }

    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
