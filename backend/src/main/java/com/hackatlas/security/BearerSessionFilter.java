package com.hackatlas.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import com.hackatlas.auth.AuthService;
import com.hackatlas.auth.lifecycle.LifecycleSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class BearerSessionFilter extends OncePerRequestFilter {
    private final AuthService sessions;
    private final SecurityProblems problems;
    private final LifecycleSettings lifecycle;

    BearerSessionFilter(AuthService sessions, SecurityProblems problems, LifecycleSettings lifecycle) {
        this.sessions = sessions;
        this.problems = problems;
        this.lifecycle = lifecycle;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Public catalogue endpoints do not become private because a client has an old token.
        return !(path.equals("/api/v1/me") || path.startsWith("/api/v1/me/")
                || path.equals("/api/v1/auth/logout") || path.equals("/api/v1/suggestions")
                || path.matches("/api/v1/hackathons/[^/]+/reports")
                || lifecycle.enabled() && (verification(path)
                    || reset(path) && request.getHeader("Authorization") != null));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1 || !headers.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)) {
            problems.unauthorized(request, response);
            return;
        }
        try {
            var principal = sessions.authenticate(headers.getFirst().substring(7));
            if (principal.isEmpty()) {
                problems.unauthorized(request, response);
                return;
            }
            if (lifecycle.enabled() && !reset(request.getServletPath()) && !pendingAllowed(request)) {
                sessions.requireVerified(principal.get());
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal.get(), null, List.of()));
            SecurityContextHolder.setContext(context);
        } catch (RuntimeException exception) {
            problems.failure(request, response, exception);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean verification(String path) {
        return path.equals("/api/v1/auth/email-verification/request") || path.equals("/api/v1/auth/email-verification/confirm");
    }
    private static boolean reset(String path) {
        return java.util.Set.of("/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/verify", "/api/v1/auth/password-reset/confirm").contains(path);
    }
    private static boolean pendingAllowed(HttpServletRequest request) {
        String path = request.getServletPath();
        return request.getMethod().equals("GET") && path.equals("/api/v1/me")
                || request.getMethod().equals("POST") && (verification(path)
                || path.equals("/api/v1/auth/logout") || path.equals("/api/v1/me/delete-account"));
    }
}
