package com.hackatlas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hackatlas.demo.enabled=false")
@ActiveProfiles("test")
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationClosedIT extends B2HttpSupport {
    @Test
    void registrationIsClosedByDefaultEvenInTestProfile() throws Exception {
        var problem = call("POST", "/auth/register", null, """
                {"email":"closed@example.com","password":"Test-password-2026","displayName":"Test"}
                """, 403);
        assertThat(problem.path("code").stringValue()).isEqualTo("FORBIDDEN");
    }
}
