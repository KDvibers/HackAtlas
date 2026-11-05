package com.hackatlas.auth.lifecycle;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "hackatlas.lifecycle.enabled", havingValue = "true")
class LifecycleRuntimeConfiguration {
    @Bean EmailActionCrypto emailActionCrypto(LifecycleSettings settings) { return new EmailActionCrypto(settings.hmacKey()); }
    @Bean EmailActionStore emailActionStore(JdbcTemplate jdbc, Clock clock, EmailActionCrypto crypto) { return new EmailActionStore(jdbc, clock, crypto); }
    @Bean LifecycleRateLimiter lifecycleRateLimiter(Clock clock, LifecycleSettings settings) {
        return new LifecycleRateLimiter(clock, settings.requestIpLimit(), settings.requestEmailLimit(), settings.verifyIpLimit(), settings.maxKeys());
    }
    @Bean(destroyMethod = "close") LifecycleMailQueue lifecycleMailQueue(LifecycleSettings settings) { return new LifecycleMailQueue(settings.queueCapacity()); }
    @Bean LifecycleMailer lifecycleMailer(LifecycleSettings settings) { return new LifecycleMailer(settings); }
}
