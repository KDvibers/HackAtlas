package com.hackatlas;

import com.hackatlas.catalog.CatalogDemoSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HackAtlasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "hackatlas.demo.enabled=true")
@ActiveProfiles({"test", "prod"})
@Import(IntegrationTestConfiguration.class)
class ProductionDemoGateIT {

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
}
