package com.waitero.back.config;

import com.waitero.back.service.AdminAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@RequiredArgsConstructor
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AdminAuditService adminAuditService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (shouldAudit(request)) {
                adminAuditService.recordCurrentImpersonationMutation(
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus()
                );
            }
        }
    }

    private boolean shouldAudit(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        return MUTATING_METHODS.contains(method)
                && uri != null
                && uri.startsWith("/api/")
                && !uri.startsWith("/api/admin/")
                && !uri.startsWith("/api/auth/")
                && !uri.startsWith("/api/tracking/")
                && !uri.startsWith("/api/customer/");
    }
}


