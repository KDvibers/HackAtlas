package com.hackatlas.security;

import java.io.IOException;
import com.hackatlas.auth.AuthRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

final class AdminLoginRateLimitFilter extends OncePerRequestFilter {
    private final AuthRateLimiter limiter;
    private final SecurityProblems problems;

    AdminLoginRateLimitFilter(AuthRateLimiter limiter, SecurityProblems problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getServletPath().equals("/admin/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limiter.checkIp(request.getRemoteAddr());
            if (request.getContentLengthLong() > 16384) {
                problems.badRequest(request, response);
                return;
            }
        } catch (RuntimeException exception) {
            problems.failure(request, response, exception);
            return;
        }
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException exception) {
            // Includes transaction-start failures outside the provider method's try/catch.
            problems.failure(request, response, exception);
        }
    }
}
