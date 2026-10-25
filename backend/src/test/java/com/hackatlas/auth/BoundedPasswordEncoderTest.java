package com.hackatlas.auth;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.hackatlas.web.ApiFailure;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BoundedPasswordEncoderTest {
    @Test
    void fifthConcurrentHashFailsImmediatelyAndCapacityReturnsAfterCompletion() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        PasswordEncoder delegate = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test worker timed out");
                    }
                    return "hash";
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };
        var encoder = new BoundedPasswordEncoder(delegate, 4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var operations = new ArrayList<Future<String>>();
            try {
                for (int i = 0; i < 4; i++) {
                    operations.add(executor.submit(() -> encoder.encode("password")));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var failure = catchThrowableOfType(() -> encoder.matches("password", "hash"), ApiFailure.class);
                assertThat(failure.status()).isEqualTo(429);
                assertThat(failure.code()).isEqualTo("RATE_LIMITED");
                assertThat(failure.retryAfter()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            for (var operation : operations) {
                assertThat(operation.get(5, TimeUnit.SECONDS)).isEqualTo("hash");
            }
            assertThat(encoder.matches("password", "hash")).isTrue();
        }
    }

    @Test
    void delegateFailureReleasesThePermit() {
        PasswordEncoder delegate = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                throw new IllegalArgumentException("Synthetic encoder failure");
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return true;
            }
        };
        var encoder = new BoundedPasswordEncoder(delegate, 1);
        assertThatThrownBy(() -> encoder.encode("password")).isInstanceOf(IllegalArgumentException.class);
        assertThat(encoder.matches("password", "hash")).isTrue();
    }
}
