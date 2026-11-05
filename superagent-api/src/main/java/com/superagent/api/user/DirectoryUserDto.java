package com.superagent.api.user;

/** A colleague as any signed-in user may see them (to invite to an agent): no roles, no contact details. */
public record DirectoryUserDto(String username, String displayName) {
}
