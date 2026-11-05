package com.hackatlas.auth.lifecycle;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.hackatlas.web.ApiFailure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LifecycleRateLimiterTest {
    @Test
    void emailCooldownAppliesToUnknownAddressesAndBlockedAttemptsDoNotExtendIt() {
        var clock = new MutableClock();
        var limiter = new LifecycleRateLimiter(clock, 10, 5, 30, 100);
        limiter.request("192.0.2.1", "unknown@example.com");
        clock.advance(59);
        var failure = catchThrowableOfType(() -> limiter.request("192.0.2.2", "unknown@example.com"), ApiFailure.class);
        assertThat(failure.status()).isEqualTo(429);
        assertThat(failure.retryAfter()).isEqualTo(1);
        clock.advance(1);
        limiter.request("192.0.2.2", "unknown@example.com");
    }

    @Test
    void hourlyEmailQuotaAndVerifyIpQuotaAreIndependentAndEventuallyExpire() {
        var clock = new MutableClock();
        var limiter = new LifecycleRateLimiter(clock, 10, 5, 2, 100);
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.request("192.0.2.1", "owner@example.com");
            clock.advance(61);
        }
        assertThat(catchThrowableOfType(() -> limiter.request("192.0.2.1", "owner@example.com"), ApiFailure.class).status()).isEqualTo(429);
        limiter.verify("192.0.2.1");
        limiter.verify("192.0.2.1");
        assertThat(catchThrowableOfType(() -> limiter.verify("192.0.2.1"), ApiFailure.class).status()).isEqualTo(429);
        clock.advance(3600);
        limiter.request("192.0.2.1", "owner@example.com");
        limiter.verify("192.0.2.1");
    }

    @Test
    void capacityNeverEvictsAnActiveAbusersKeyAndDiagnosticsContainNoAddress() {
        var clock = new MutableClock();
        var limiter = new LifecycleRateLimiter(clock, 1, 1, 1, 1);
        limiter.verify("192.0.2.1");
        var failure = catchThrowableOfType(() -> limiter.verify("192.0.2.2"), ApiFailure.class);
        assertThat(failure.status()).isEqualTo(429);
        assertThat(failure.getMessage()).doesNotContain("192.0.2");
        assertThat(catchThrowableOfType(() -> limiter.verify("192.0.2.1"), ApiFailure.class).status()).isEqualTo(429);
        clock.advance(900);
        limiter.verify("192.0.2.2");
    }

    private static final class MutableClock extends Clock {
        private Instant value = Instant.parse("2026-11-03T10:00:00Z");
        void advance(long seconds) { value = value.plusSeconds(seconds); }
        @Override public Instant instant() { return value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
    }
}
