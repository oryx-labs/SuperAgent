package com.superagent.web.automation;

/** Thrown when an automation does not exist; mapped to HTTP 404. */
public class AutomationNotFoundException extends RuntimeException {

    public AutomationNotFoundException(String id) {
        super("No such automation: " + id);
    }
}
