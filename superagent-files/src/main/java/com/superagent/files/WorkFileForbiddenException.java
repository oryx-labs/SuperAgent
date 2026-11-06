package com.superagent.files;

import com.superagent.core.error.ForbiddenException;

/** The caller can see the file but may not rename or delete it. */
public class WorkFileForbiddenException extends ForbiddenException {

    public WorkFileForbiddenException(String message) {
        super(message);
    }
}
