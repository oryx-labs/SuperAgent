package com.superagent.web.auth;

import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.platform.tenant.CompanyService;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves a sign-in: the organization and the username in it (see {@link #KEY_SEPARATOR}), to that company's user.
 * Every account is a provisioned, enabled LOCAL user in the {@link UserStore}; there is no configured one.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    /** Joins the organization and the username into the one name Spring's form login passes along. */
    static final char KEY_SEPARATOR = '\n';

    private final UserStore users;
    private final CompanyService companies;

    public AppUserDetailsService(UserStore users, CompanyService companies) {
        this.users = users;
        this.companies = companies;
    }

    @Override
    public UserDetails loadUserByUsername(String key) {
        int at = key == null ? -1 : key.indexOf(KEY_SEPARATOR);
        if (at <= 0) {
            throw new UsernameNotFoundException("No organization given");
        }
        String org = key.substring(0, at).strip().toLowerCase(java.util.Locale.ROOT);
        String username = key.substring(at + 1).strip();
        String company = companies.enabledCompany(org)
                .orElseThrow(() -> new UsernameNotFoundException("No such organization: " + org));
        User u = TenantContext.call(company, () -> users.findByUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));
        if (u.passwordHash() == null || u.passwordHash().isBlank()) {
            // non-local account (phone / SSO / WeChat): cannot sign in with a password
            throw new UsernameNotFoundException("User has no password login: " + username);
        }
        return new TenantUser(u.id(), company, u.username(), u.passwordHash(), u.enabled(),
                AuthorityUtils.createAuthorityList(authorities(u.roles())));
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
