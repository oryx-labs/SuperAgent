package com.superagent.web.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * A signed-in person: the principal's name is their user id (what everything they own records), and the company
 * they signed in to is carried along, so each request runs as it without looking it up.
 */
public class TenantUser extends User {

    private final String companyId;
    private final String loginName;

    TenantUser(String userId, String companyId, String loginName, String passwordHash, boolean enabled,
               Collection<? extends GrantedAuthority> authorities) {
        super(userId, passwordHash, enabled, true, true, true, authorities);
        this.companyId = companyId;
        this.loginName = loginName;
    }

    public String companyId() {
        return companyId;
    }

    /** The name they typed to sign in (their username in the company). */
    public String loginName() {
        return loginName;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TenantUser t && super.equals(o) && companyId.equals(t.companyId);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + companyId.hashCode();
    }
}
