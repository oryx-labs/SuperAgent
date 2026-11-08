package com.superagent.web.auth;

import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.platform.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves credentials for username/password login. The config-seeded admin is
 * always available (its hash is computed at startup from
 * {@code superagent.auth.admin.*}); every other user must be a provisioned,
 * enabled LOCAL account in the {@link UserStore}.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AppUserDetailsService.class);

    private final UserStore users;
    private final CompanyService companies;
    private final String adminUsername;
    private final String adminPasswordHash;

    public AppUserDetailsService(
            UserStore users,
            CompanyService companies,
            PasswordEncoder encoder,
            @Value("${superagent.auth.admin.username:admin}") String adminUsername,
            @Value("${superagent.auth.admin.password:}") String adminPassword) {
        this.users = users;
        this.companies = companies;
        this.adminUsername = adminUsername;
        // No weak default: if the admin password is unset, generate a strong one-time
        // password and log it once, so there is always a way in but never a known default.
        String password = adminPassword;
        if (password == null || password.isBlank()) {
            password = randomPassword();
            log.warn("No superagent.auth.admin.password set — generated a one-time admin password for '{}': {}  "
                    + "(set superagent.auth.admin.password / SUPERAGENT_ADMIN_PASSWORD to choose your own)",
                    adminUsername, password);
        }
        this.adminPasswordHash = encoder.encode(password);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        if (adminUsername.equals(username)) {
            return org.springframework.security.core.userdetails.User.withUsername(username)
                    .password(adminPasswordHash)
                    .authorities("ROLE_ADMIN", "ROLE_USER")
                    .build();
        }
        // Usernames are global: the username says which company to look the user up in.
        String company = companies.companyOf(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        User u = TenantContext.call(company, () -> users.findByUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        if (u.passwordHash() == null || u.passwordHash().isBlank()) {
            // non-local account (phone / SSO / WeChat): cannot sign in with a password
            throw new UsernameNotFoundException("User has no password login: " + username);
        }
        return org.springframework.security.core.userdetails.User.withUsername(username)
                .password(u.passwordHash())
                .disabled(!u.enabled())
                .authorities(authorities(u.roles()))
                .build();
    }

    /** Maps bare role names to Spring authorities, always including ROLE_USER. */
    static String[] authorities(Set<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        out.add("ROLE_USER");
        if (roles != null) {
            roles.forEach(r -> out.add(r.startsWith("ROLE_") ? r : "ROLE_" + r));
        }
        return out.toArray(String[]::new);
    }
}
