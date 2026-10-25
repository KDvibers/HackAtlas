package com.hackatlas;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.auth.registration-enabled=true", "hackatlas.auth.rate-limit.ip-limit=1000",
                "hackatlas.auth.rate-limit.account-limit=1000", "hackatlas.moderation.rate-limit.ip-limit=4",
                "hackatlas.moderation.rate-limit.account-limit=2", "hackatlas.moderation.rate-limit.window=PT1H"})
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModerationRateLimitIT extends B2HttpSupport {
    @Override String contractPhase() { return "B3"; }

    @Test
    void accountAndIpLimitsCoverBothModerationOperationsAndIgnoreSpoofedForwarding() throws Exception {
        var first = register("moderation-rate-a@example.com", PASSWORD, "First").path("accessToken").stringValue();
        var second = register("moderation-rate-b@example.com", PASSWORD, "Second").path("accessToken").stringValue();
        var suggestion = "{\"url\":\"https://example.com/event\",\"comment\":null}";
        var report = "{\"reason\":\"OTHER\",\"message\":\"Проверьте сведения\"}";
        call("POST", "/suggestions", first, suggestion, 202);
        call("POST", "/hackathons/" + AI + "/reports", first, report, 202);
        var accountLimit = request("POST", "/suggestions", first, suggestion, 429);
        assertThat(accountLimit.headers().firstValue("Retry-After")).contains("3600");
        call("POST", "/hackathons/" + AI + "/reports", second, report, 202);
        var ipLimit = request("POST", "/hackathons/" + AI + "/reports", second, report, 429,
                Map.of("X-Forwarded-For", "203.0.113.5", "Forwarded", "for=203.0.113.6"));
        assertThat(ipLimit.headers().firstValue("Retry-After")).contains("3600");
        assertThat(body(ipLimit).path("code").stringValue()).isEqualTo("RATE_LIMITED");
    }
}
