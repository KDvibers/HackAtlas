package com.hackatlas.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock applicationClock(
            Environment environment,
            @Value("${hackatlas.demo.enabled:false}") boolean demoEnabled,
            @Value("${hackatlas.demo.as-of:}") String demoAsOf
    ) {
        if (demoAsOf.isBlank()) {
            return Clock.systemUTC();
        }
        if (!demoEnabled || !environment.acceptsProfiles(Profiles.of("(dev | test) & !prod"))) {
            throw new IllegalStateException("A fixed demo clock requires an enabled dev/test demo profile");
        }
        return Clock.fixed(Instant.parse(demoAsOf), ZoneOffset.UTC);
    }
}
