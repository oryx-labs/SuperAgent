package com.superagent.web.task;

/** Thrown when a requested task artifact does not exist; mapped to HTTP 404. */
public class ArtifactNotFoundException extends RuntimeException {

    public ArtifactNotFoundException(String name) {
        super("No such artifact: " + name);
    }
}
