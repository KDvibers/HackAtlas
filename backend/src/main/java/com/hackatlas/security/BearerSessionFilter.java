package com.hackatlas.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import com.hackatlas.auth.AuthService;
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

    BearerSessionFilter(AuthService sessions, SecurityProblems problems) {
        this.sessions = sessions;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // Public catalogue endpoints do not become private because a client has an old token.
        return !(path.equals("/api/v1/me") || path.startsWith("/api/v1/me/")
                || path.equals("/api/v1/auth/logout") || path.equals("/api/v1/suggestions")
                || path.matches("/api/v1/hackathons/[^/]+/reports"));
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
}
