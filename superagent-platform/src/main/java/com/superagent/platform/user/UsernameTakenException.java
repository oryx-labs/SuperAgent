package com.superagent.platform.user;

import com.superagent.core.error.ConflictException;

/** Thrown when creating a user whose username is already taken; mapped to HTTP 409. */
public class UsernameTakenException extends ConflictException {
    public UsernameTakenException(String username) {
        super("Username already exists: " + username);
    }
}
