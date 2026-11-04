package com.superagent.web.automation;

/** Thrown when the caller may not act on an automation (not its owner, or a bad API token); mapped to HTTP 403. */
public class AutomationForbiddenException extends RuntimeException {

    public AutomationForbiddenException(String message) {
        super(message);
    }
}
