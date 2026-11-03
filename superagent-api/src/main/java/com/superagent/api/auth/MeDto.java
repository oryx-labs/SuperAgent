package com.superagent.api.auth;

import java.util.List;

/** The currently signed-in user, for the SPA to show identity and gate admin UI. */
public record MeDto(String username, String displayName, List<String> roles, boolean admin) {
}
