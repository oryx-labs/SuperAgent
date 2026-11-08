package com.superagent.web.auth;

import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.tenant.CompanyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs each signed-in request as the user's company. Unauthenticated endpoints that still touch company data
 * (the MCP endpoint, automation triggers) set the company themselves from what authenticates them.
 */
class TenantFilter extends OncePerRequestFilter {

    private final CompanyService companies;

    TenantFilter(CompanyService companies) {
        this.companies = companies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String company = auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                ? null : companies.companyOf(auth.getName()).orElse(null);
        if (company == null) {
            chain.doFilter(request, response);
            return;
        }
        TenantContext.set(company);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
