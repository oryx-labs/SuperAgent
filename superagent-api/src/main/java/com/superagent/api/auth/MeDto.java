package com.superagent.api.auth;

import java.util.List;

/** The currently signed-in user, for the SPA to show identity (with their teams and company) and gate admin UI. */
public record MeDto(String username, String displayName, List<String> roles, boolean admin, List<String> teams,
                    String companyName, String companyShortName) {
}
