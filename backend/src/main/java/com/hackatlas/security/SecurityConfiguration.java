package com.hackatlas.security;

import com.hackatlas.auth.AuthRateLimiter;
import com.hackatlas.auth.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {
    @Bean
    WebSecurityCustomizer rejectedRequests(SecurityProblems problems) {
        // Preserve StrictHttpFirewall's checks while returning the same safe API error envelope.
        return web -> web.requestRejectedHandler((request, response, exception) -> problems.badRequest(request, response));
    }

    @Bean
    AuthenticationManager opaqueSessionAuthenticationManager() {
        // Suppresses Boot's generated in-memory user; this application has no password/basic/form chain.
        return authentication -> {
            throw new AuthenticationServiceException("Only opaque bearer sessions are supported");
        };
    }

    @Bean
    @Order(1)
    SecurityFilterChain nativeApi(
            HttpSecurity http, AuthService sessions, AuthRateLimiter limiter, SecurityProblems problems
    ) throws Exception {
        return http.securityMatcher("/api/v1/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/me", "/api/v1/me/**", "/api/v1/auth/logout").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problems.unauthorized(request, response))
                        .accessDeniedHandler((request, response, exception) -> problems.forbidden(request, response)))
                .addFilterBefore(new AuthIpRateLimitFilter(limiter, problems), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new BearerSessionFilter(sessions, problems), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain otherRoutes(HttpSecurity http, SecurityProblems problems) throws Exception {
        // Future browser admin gets its own session/form chain in B3. CSRF stays enabled here.
        return http.csrf(Customizer.withDefaults())
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin", "/admin/**").denyAll()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problems.forbidden(request, response))
                        .accessDeniedHandler((request, response, exception) -> problems.forbidden(request, response)))
                .build();
    }
}
