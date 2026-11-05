package com.hackatlas.auth.lifecycle;

import java.time.Clock;
import java.time.Duration;

import com.hackatlas.auth.AuthRateLimiter;

/** Separate bounded admission buckets; none consumes the existing login quotas. */
public final class LifecycleRateLimiter {
    private final AuthRateLimiter requestIp;
    private final AuthRateLimiter requestEmail;
    private final AuthRateLimiter emailCooldown;
    private final AuthRateLimiter verifyIp;

    public LifecycleRateLimiter(Clock clock, int requestLimit, int emailLimit, int verifyLimit, int maxKeys) {
        requestIp = new AuthRateLimiter(clock, Duration.ofMinutes(15), requestLimit, 1, maxKeys);
        requestEmail = new AuthRateLimiter(clock, Duration.ofHours(1), 1, emailLimit, maxKeys);
        emailCooldown = new AuthRateLimiter(clock, Duration.ofSeconds(60), 1, 1, maxKeys);
        verifyIp = new AuthRateLimiter(clock, Duration.ofMinutes(15), verifyLimit, 1, maxKeys);
    }

    public void request(String ip, String normalizedEmail) {
        requestIp.checkIp(ip);
        requestEmail.checkAccount(normalizedEmail);
        emailCooldown.checkAccount(normalizedEmail);
    }

    public void verify(String ip) { verifyIp.checkIp(ip); }
}
