package com.superagent.task;

import com.superagent.core.error.NotFoundException;

/** Thrown when a requested task artifact does not exist; mapped to HTTP 404. */
public class ArtifactNotFoundException extends NotFoundException {

    public ArtifactNotFoundException(String name) {
        super("No such artifact: " + name);
    }
}
