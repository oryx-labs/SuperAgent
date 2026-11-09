package com.superagent.api.auth;

import java.util.List;

/**
 * The currently signed-in user, for the SPA to show identity (with their teams and company) and gate admin UI.
 * {@code avatarVersion} is null without an avatar; {@code canChangePassword} only for username/password accounts.
 */
/**
 * The signed-in person. {@code id} is who they are (what owners, members and authors record — compare with it);
 * {@code username} is how they sign in, within organization {@code org}. {@code mustChangePassword}: they signed in
 * with a password someone else set and must choose their own before doing anything else.
 */
public record MeDto(String id, String username, String displayName, List<String> roles, boolean admin, List<String> teams,
                    String companyName, String companyShortName, Long avatarVersion, boolean canChangePassword,
                    String org, boolean mustChangePassword) {
}
