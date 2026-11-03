package com.superagent.web.auth;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.auth.MeDto;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
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

    public AuthController(UserStore users) {
        this.users = users;
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
        return ApiResponse.ok(new MeDto(username, displayName == null ? username : displayName, roles, admin));
    }
}
