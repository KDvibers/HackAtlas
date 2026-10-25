package com.hackatlas.security;

import java.io.IOException;
import com.hackatlas.admin.AdminAccessService;
import com.hackatlas.admin.AdminPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class AdminSessionValidityFilter extends OncePerRequestFilter {
    private final AdminAccessService access;
    private final SecurityProblems problems;

    AdminSessionValidityFilter(AdminAccessService access, SecurityProblems problems) {
        this.access = access;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AdminPrincipal principal) {
            try {
                if (!access.allowed(principal.userId())) {
                    SecurityContextHolder.clearContext();
                    if (request.getSession(false) != null) { request.getSession(false).invalidate(); }
                    problems.forbidden(request, response);
                    return;
                }
            } catch (RuntimeException exception) {
                problems.failure(request, response, exception);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
