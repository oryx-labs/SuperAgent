package com.superagent.platform.user;

import com.superagent.core.error.NotFoundException;

/** Thrown when a user id does not resolve; mapped to HTTP 404. */
public class UserNotFoundException extends NotFoundException {
    public UserNotFoundException(String id) {
        super("No such user: " + id);
    }
}
