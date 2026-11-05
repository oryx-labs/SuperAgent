package com.superagent.core.error;

/**
 * The request clashes with current state (a taken name, a run in progress); mapped to HTTP 409 "conflict". Modules extend this base type, so the HTTP layer maps errors without knowing every module.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
