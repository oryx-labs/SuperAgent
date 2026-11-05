package com.superagent.core.error;

/**
 * The caller may see the thing but not do this to it; mapped to HTTP 403. Modules extend this base type, so the HTTP layer maps errors without knowing every module.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
