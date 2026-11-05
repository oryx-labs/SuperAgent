package com.superagent.task.automation;

import com.superagent.core.error.ForbiddenException;

/** Thrown when the caller may not act on an automation (not its owner, or a bad API token); mapped to HTTP 403. */
public class AutomationForbiddenException extends ForbiddenException {

    public AutomationForbiddenException(String message) {
        super(message);
    }
}
