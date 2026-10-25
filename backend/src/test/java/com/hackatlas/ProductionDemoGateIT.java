package com.hackatlas;

import com.hackatlas.catalog.CatalogDemoSeeder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hackatlas.demo.enabled=true", "hackatlas.auth.registration-enabled=true"})
@ActiveProfiles({"test", "prod"})
@Import(IntegrationTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionDemoGateIT extends B2HttpSupport {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void productionProfileDisablesDemoEvenWhenTheFlagAndTestProfileArePresent() {
        assertThat(context.getBeansOfType(CatalogDemoSeeder.class)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_hackathon", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.catalog_tag", Integer.class)).isZero();
    }

    @Test
    void productionCannotAccidentallyEnablePrivateTestSignup() throws Exception {
        var problem = call("POST", "/auth/register", null, """
                {"email":"production@example.com","password":"Test-password-2026","displayName":"Test"}
                """, 403);
        assertThat(problem.path("code").stringValue()).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.user_account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from hackatlas.auth_session", Integer.class)).isZero();
    }
}
