package com.hackatlas.security;

import com.hackatlas.auth.AuthRateLimiter;
import com.hackatlas.auth.AuthService;
import com.hackatlas.admin.AdminAccessService;
import com.hackatlas.admin.AdminAuthenticationProvider;
import com.hackatlas.admin.AdminLoginException;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

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
        // Native API never uses password/basic/form authentication or a generated in-memory user.
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
                        .requestMatchers("/api/v1/me", "/api/v1/me/**", "/api/v1/auth/logout",
                                "/api/v1/suggestions", "/api/v1/hackathons/*/reports").authenticated()
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
    SecurityFilterChain browserAdmin(HttpSecurity http, AdminAuthenticationProvider provider,
            AdminAccessService access, AuthRateLimiter limiter, SecurityProblems problems) throws Exception {
        return http.securityMatcher("/admin", "/admin/**")
                .authenticationManager(new ProviderManager(provider))
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin/login", "/admin/admin.css").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form.loginPage("/admin/login").loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin", true)
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof AdminLoginException limited) {
                                String code = limited.status() == 429 ? "RATE_LIMITED"
                                        : limited.status() == 503 ? "SERVICE_UNAVAILABLE" : "INTERNAL_ERROR";
                                problems.failure(request, response, new com.hackatlas.web.ApiFailure(
                                        limited.status(), code, "Вход не выполнен. Повторите позже.", limited.retryAfter()));
                            } else {
                                response.sendRedirect("/admin/login?error");
                            }
                        }).permitAll())
                .logout(logout -> logout.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout")
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .addLogoutHandler((request,response,authentication) -> {
                            var cookie = new jakarta.servlet.http.Cookie("HACKATLAS_ADMIN_SESSION", "");
                            cookie.setPath("/admin");
                            cookie.setMaxAge(0);
                            cookie.setHttpOnly(true);
                            cookie.setSecure(request.getServletContext().getSessionCookieConfig().isSecure());
                            cookie.setAttribute("SameSite","Strict");
                            response.addCookie(cookie);
                        }))
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler((request, response, exception) -> problems.forbidden(request, response)))
                .addFilterBefore(new AdminLoginRateLimitFilter(limiter, problems), CsrfFilter.class)
                .addFilterBefore(new AdminSessionValidityFilter(access, problems), AuthorizationFilter.class)
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain otherRoutes(HttpSecurity http, SecurityProblems problems) throws Exception {
        // CSRF remains enabled on non-API routes as well.
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
