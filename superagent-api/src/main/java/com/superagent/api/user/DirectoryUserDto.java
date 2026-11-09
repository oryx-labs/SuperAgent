package com.superagent.api.user;

/** A colleague as any signed-in user may see them (to invite to an agent): no roles, no contact details. */
/** {@code id}: who they are (what pages send back); {@code username}: how they sign in, shown beside the name. */
public record DirectoryUserDto(String id, String username, String displayName) {
}
