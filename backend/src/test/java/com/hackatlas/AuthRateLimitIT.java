package com.hackatlas;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=4",
                "hackatlas.auth.rate-limit.account-limit=2", "hackatlas.auth.rate-limit.window=PT15M"})
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRateLimitIT extends B2HttpSupport {
    @Test
    void accountAndIpLimitsSpanLoginAndSignupAndForwardedHeadersDoNotBypassThem() throws Exception {
        register("limited@example.com", PASSWORD, "Limited");
        call("POST", "/auth/login", null, loginPayload("LIMITED@example.com", "wrong"), 401);
        var accountLimited = request("POST", "/auth/login", null,
                loginPayload("limited@example.com", PASSWORD), 429);
        assertThat(body(accountLimited).path("code").stringValue()).isEqualTo("RATE_LIMITED");
        assertThat(accountLimited.headers().firstValue("Retry-After")).contains("900");
        call("POST", "/auth/login", null, loginPayload("different@example.com", "wrong"), 401);
        var ipLimited = request("POST", "/auth/register", null, """
                {"email":"another@example.com","password":"Test-password-2026","displayName":"Test"}
                """, 429, Map.of("X-Forwarded-For", "203.0.113.5", "Forwarded", "for=203.0.113.6"));
        assertThat(ipLimited.headers().firstValue("Retry-After")).contains("900");
        assertThat(body(ipLimited).path("code").stringValue()).isEqualTo("RATE_LIMITED");
    }
}
