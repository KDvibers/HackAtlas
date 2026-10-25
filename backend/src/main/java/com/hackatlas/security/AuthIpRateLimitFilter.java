package com.hackatlas.security;

import java.io.IOException;

import com.hackatlas.auth.AuthRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

final class AuthIpRateLimitFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    private final SecurityProblems problems;

    AuthIpRateLimitFilter(AuthRateLimiter limiter, SecurityProblems problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !request.getMethod().equals("POST")
                || !(path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/login")
                     || path.equals("/api/v1/me/delete-account"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // Forwarded headers are untrusted unless an explicitly configured ingress validates them.
            limiter.checkIp(request.getRemoteAddr());
        } catch (RuntimeException exception) {
            problems.failure(request, response, exception);
            return;
        }
        chain.doFilter(request, response);
    }
}
