package com.superagent.task.automation;

import com.superagent.core.error.NotFoundException;

/** Thrown when an automation does not exist; mapped to HTTP 404. */
public class AutomationNotFoundException extends NotFoundException {

    public AutomationNotFoundException(String id) {
        super("No such automation: " + id);
    }
}
