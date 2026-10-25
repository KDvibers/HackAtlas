package com.hackatlas.moderation;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import com.hackatlas.auth.AuthRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModerationRateLimiter {
    private final AuthRateLimiter windows;

    public ModerationRateLimiter(Clock clock,
            @Value("${hackatlas.moderation.rate-limit.window:PT1H}") Duration window,
            @Value("${hackatlas.moderation.rate-limit.ip-limit:60}") int ipLimit,
            @Value("${hackatlas.moderation.rate-limit.account-limit:20}") int accountLimit,
            @Value("${hackatlas.moderation.rate-limit.max-keys:10000}") int maxKeys) {
        // Reuse the tested bounded/concurrent implementation, with independent submission buckets.
        windows = new AuthRateLimiter(clock, window, ipLimit, accountLimit, maxKeys);
    }

    public void check(String remoteAddress, UUID userId) {
        windows.checkIp(remoteAddress);
        windows.checkAccount(userId.toString());
    }
}
