package com.superagent.web.auth;

import com.superagent.platform.user.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Someone who signed in with a password someone else set (generated at sign-up, the built-in default, or set by an
 * admin) can do nothing but choose their own: every other API call is refused until they have. Once it's changed,
 * the session remembers it, so the check costs nothing afterwards.
 */
class PasswordChangeFilter extends OncePerRequestFilter {

    static final String CLEARED = "sa.passwordOk";

    private final AccountService account;

    PasswordChangeFilter(AccountService account) {
        this.account = account;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/v1/auth/") || uri.equals("/api/v1/account/password")
                || uri.equals("/api/v1/meta") || uri.startsWith("/api/v1/branding") || uri.startsWith("/api/v1/orgs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (!(auth != null && auth.getPrincipal() instanceof TenantUser) || session == null
                || Boolean.TRUE.equals(session.getAttribute(CLEARED))) {
            chain.doFilter(request, response);
            return;
        }
        if (account.mustChangePassword(auth.getName())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"password_change_required\",\"message\":\"请先修改初始密码\"}");
            return;
        }
        session.setAttribute(CLEARED, Boolean.TRUE);
        chain.doFilter(request, response);
    }
}
