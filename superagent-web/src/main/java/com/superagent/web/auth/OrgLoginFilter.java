package com.superagent.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A sign-in names an organization as well as a username (usernames are only unique within one). Spring's form login
 * passes a single name on, so this joins the two for {@link AppUserDetailsService} to split again.
 */
class OrgLoginFilter extends OncePerRequestFilter {

    static final String LOGIN = "/api/v1/auth/login";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && LOGIN.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String org = request.getParameter("org");
        String username = request.getParameter("username");
        String key = (org == null ? "" : org.strip()) + AppUserDetailsService.KEY_SEPARATOR + (username == null ? "" : username.strip());
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                return "username".equals(name) ? key : super.getParameter(name);
            }
        }, response);
    }
}
