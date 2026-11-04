package com.superagent.web.knowledge;

/** A knowledge-base path that doesn't exist (or isn't reachable); mapped to HTTP 404. */
public class KnowledgeNotFoundException extends RuntimeException {

    public KnowledgeNotFoundException(String path) {
        super("No such file or folder: " + (path == null || path.isBlank() ? "/" : path));
    }
}
