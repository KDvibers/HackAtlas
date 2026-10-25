package com.hackatlas.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.hackatlas.web.ApiFailure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AuthRateLimiterTest {
    @Test
    void ipAndAccountUseIndependentLimitsAndBlockedAttemptsDoNotExtendTheWindow() {
        var clock = new MutableClock();
        var limiter = new AuthRateLimiter(clock, Duration.ofSeconds(10), 2, 1, 10);
        limiter.checkIp("127.0.0.1");
        limiter.checkIp("127.0.0.1");
        limiter.checkAccount("person@example.test");

        clock.advance(Duration.ofMillis(6500));
        var failure = catchThrowableOfType(() -> limiter.checkIp("127.0.0.1"), ApiFailure.class);
        assertThat(failure.status()).isEqualTo(429);
        assertThat(failure.code()).isEqualTo("RATE_LIMITED");
        assertThat(failure.retryAfter()).isEqualTo(4);
        assertThatThrownBy(() -> limiter.checkAccount("person@example.test")).isInstanceOf(ApiFailure.class);

        clock.advance(Duration.ofMillis(3500));
        limiter.checkIp("127.0.0.1");
        limiter.checkAccount("person@example.test");
    }

    @Test
    void aFullStoreRejectsNewKeysWithoutEvictingAnActiveAbusersLimit() {
        var clock = new MutableClock();
        var limiter = new AuthRateLimiter(clock, Duration.ofSeconds(5), 1, 1, 2);
        limiter.checkIp("192.0.2.1");
        limiter.checkAccount("existing@example.test");
        assertThatThrownBy(() -> limiter.checkIp("192.0.2.2")).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> limiter.checkIp("192.0.2.1")).isInstanceOf(ApiFailure.class);

        clock.advance(Duration.ofSeconds(5));
        limiter.checkIp("192.0.2.2");
        limiter.checkAccount("new@example.test");
    }

    @Test
    void simultaneousAttemptsCannotExceedTheLimit() throws Exception {
        var limiter = new AuthRateLimiter(new MutableClock(), Duration.ofMinutes(1), 20, 10, 100);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 200; i++) {
                attempts.add(() -> {
                    try {
                        limiter.checkIp("192.0.2.1");
                        return true;
                    } catch (ApiFailure failure) {
                        assertThat(failure.status()).isEqualTo(429);
                        return false;
                    }
                });
            }
            int accepted = 0;
            for (var result : executor.invokeAll(attempts)) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(20);
        }
    }

    @Test
    void invalidConfigurationCannotDisableProtection() {
        var clock = new MutableClock();
        assertThatThrownBy(() -> new AuthRateLimiter(clock, Duration.ZERO, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthRateLimiter(clock, Duration.ofMinutes(1), 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthRateLimiter(clock, Duration.ofMinutes(1), 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthRateLimiter(clock, Duration.ofMinutes(1), 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-12T10:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
