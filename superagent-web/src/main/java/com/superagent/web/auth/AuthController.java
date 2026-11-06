package com.superagent.web.auth;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.auth.MeDto;
import com.superagent.api.settings.SystemSettingsDto;
import com.superagent.core.org.OrgStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.platform.settings.SettingsService;
import com.superagent.platform.user.AccountService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Identity endpoint. Login and logout are handled by Spring Security filters at
 * {@code /api/v1/auth/login} and {@code /api/v1/auth/logout}; this exposes the
 * current user for the SPA.
 */
@RestController
public class AuthController {

    private final UserStore users;
    private final OrgStore org;
    private final SettingsService settings;
    private final AccountService account;

    public AuthController(UserStore users, OrgStore org, SettingsService settings, AccountService account) {
        this.users = users;
        this.org = org;
        this.settings = settings;
        this.account = account;
    }

    @GetMapping(ApiPaths.AUTH + "/me")
    public ApiResponse<MeDto> me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();
        String username = authentication.getName();
        String displayName = users.findByUsername(username).map(User::displayName).orElse(username);
        boolean admin = roles.contains("ADMIN");
        SystemSettingsDto company = settings.get();
        return ApiResponse.ok(new MeDto(username, displayName == null ? username : displayName, roles, admin,
                org.teams(username), company.companyName(), company.companyShortName(),
                account.avatarVersion(username), account.canChangePassword(username)));
    }
}
