package com.superagent.core.error;

/**
 * Something is already running and this must wait for it; mapped to HTTP 409 "busy". Modules extend this base type, so the HTTP layer maps errors without knowing every module.
 */
public class BusyException extends RuntimeException {

    public BusyException(String message) {
        super(message);
    }
}
