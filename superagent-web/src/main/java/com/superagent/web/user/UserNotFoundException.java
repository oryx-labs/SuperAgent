package com.superagent.web.user;

/** Thrown when a user id does not resolve; mapped to HTTP 404. */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String id) {
        super("No such user: " + id);
    }
}
