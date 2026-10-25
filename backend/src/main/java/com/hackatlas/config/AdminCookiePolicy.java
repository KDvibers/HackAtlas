package com.hackatlas.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdminCookiePolicy {
    @Bean
    ServletContextInitializer productionAdminCookies() {
        // A mixed dev/prod profile or accidental property override must not weaken production cookies.
        return servletContext -> servletContext.getSessionCookieConfig().setSecure(true);
    }
}
