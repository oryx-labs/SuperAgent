package com.superagent.web.user;

/** Thrown when creating a user whose username is already taken; mapped to HTTP 409. */
public class UsernameTakenException extends RuntimeException {
    public UsernameTakenException(String username) {
        super("Username already exists: " + username);
    }
}
