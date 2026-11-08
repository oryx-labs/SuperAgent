package com.superagent.project;

import com.superagent.core.error.ForbiddenException;

public class ProjectForbiddenException extends ForbiddenException {

    public ProjectForbiddenException(String message) {
        super(message);
    }
}
