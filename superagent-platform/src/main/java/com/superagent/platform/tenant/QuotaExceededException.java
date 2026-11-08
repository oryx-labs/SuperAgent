package com.superagent.platform.tenant;

import com.superagent.core.error.ForbiddenException;

/** The company has used up one of its limits (users, running tasks, tokens this month). */
public class QuotaExceededException extends ForbiddenException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
