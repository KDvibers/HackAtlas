package com.hackatlas;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=3",
                "hackatlas.auth.rate-limit.account-limit=2", "hackatlas.auth.rate-limit.window=PT15M"})
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDeletionRateLimitIT extends B2HttpSupport {
    @Override String contractPhase() { return "B3"; }

    @Test
    void passwordConfirmationIsLimitedByTheSameAccountAndIpAuthBudgets() throws Exception {
        var token = register("delete-rate@example.com", PASSWORD, "Limited").path("accessToken").stringValue();
        call("POST", "/me/delete-account", token, "{\"password\":\"wrong\"}", 401);
        var accountLimited = request("POST", "/me/delete-account", token, "{\"password\":\"wrong\"}", 429);
        assertThat(accountLimited.headers().firstValue("Retry-After")).contains("900");
        var ipLimited = request("POST", "/me/delete-account", token,
                json.writeValueAsString(Map.of("password", PASSWORD)), 429,
                Map.of("X-Forwarded-For", "203.0.113.7"));
        assertThat(ipLimited.headers().firstValue("Retry-After")).contains("900");
        call("GET", "/me", token, null, 200);
    }
}
