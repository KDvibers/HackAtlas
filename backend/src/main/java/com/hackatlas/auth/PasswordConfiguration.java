package com.hackatlas.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {
    @Bean
    PasswordEncoder passwordEncoder(
            @Value("${hackatlas.auth.argon2.memory-kib:19456}") int memoryKiB,
            @Value("${hackatlas.auth.argon2.iterations:2}") int iterations,
            @Value("${hackatlas.auth.password-concurrency:4}") int concurrency
    ) {
        if (memoryKiB < 19456 || iterations < 2) {
            throw new IllegalArgumentException("Argon2id requires at least 19456 KiB and 2 iterations");
        }
        return new BoundedPasswordEncoder(new Argon2PasswordEncoder(16, 32, 1, memoryKiB, iterations), concurrency);
    }
}
