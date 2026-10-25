package com.hackatlas.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.hackatlas.web.ApiFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-process, bounded fixed windows; active keys are never evicted to admit attackers. */
@Component
public class AuthRateLimiter {
    private final Clock clock;
    private final Duration window;
    private final int ipLimit;
    private final int accountLimit;
    private final int maxKeys;
    private final Map<String, Window> windows = new HashMap<>();

    public AuthRateLimiter(
            Clock clock,
            @Value("${hackatlas.auth.rate-limit.window:PT15M}") Duration window,
            @Value("${hackatlas.auth.rate-limit.ip-limit:30}") int ipLimit,
            @Value("${hackatlas.auth.rate-limit.account-limit:10}") int accountLimit,
            @Value("${hackatlas.auth.rate-limit.max-keys:10000}") int maxKeys
    ) {
        if (window.isNegative() || window.isZero() || ipLimit < 1 || accountLimit < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("Authentication rate limits must be positive");
        }
        this.clock = clock;
        this.window = window;
        this.ipLimit = ipLimit;
        this.accountLimit = accountLimit;
        this.maxKeys = maxKeys;
    }

    public void checkIp(String remoteAddress) {
        consume("ip:" + TokenHash.sha256(remoteAddress), ipLimit);
    }

    public void checkAccount(String normalizedEmail) {
        consume("account:" + TokenHash.sha256(normalizedEmail), accountLimit);
    }

    private synchronized void consume(String key, int limit) {
        Instant now = clock.instant();
        Window active = windows.get(key);
        if (active != null && !now.isBefore(active.expiresAt)) {
            windows.remove(key);
            active = null;
        }
        if (active == null) {
            if (windows.size() >= maxKeys) {
                windows.values().removeIf(entry -> !now.isBefore(entry.expiresAt));
            }
            if (windows.size() >= maxKeys) {
                Instant firstExpiry = windows.values().stream().map(entry -> entry.expiresAt)
                        .min(Instant::compareTo).orElse(now.plus(window));
                throw limited(now, firstExpiry);
            }
            active = new Window(now.plus(window));
            windows.put(key, active);
        }
        if (active.count >= limit) {
            throw limited(now, active.expiresAt);
        }
        active.count++;
    }

    private static ApiFailure limited(Instant now, Instant expiry) {
        Duration remaining = Duration.between(now, expiry);
        long seconds = Math.max(1, remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1));
        return new ApiFailure(429, "RATE_LIMITED", "Слишком много попыток. Повторите позже.", seconds);
    }

    private static final class Window {
        private final Instant expiresAt;
        private int count;

        private Window(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
