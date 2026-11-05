package com.superagent.core.error;

/**
 * A requested thing doesn't exist (or isn't visible to the caller); mapped to HTTP 404. Modules extend this base type, so the HTTP layer maps errors without knowing every module.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
