package com.superagent.knowledge;

import com.superagent.core.error.NotFoundException;

/** A knowledge-base path that doesn't exist (or isn't reachable); mapped to HTTP 404. */
public class KnowledgeNotFoundException extends NotFoundException {

    public KnowledgeNotFoundException(String path) {
        super("No such file or folder: " + (path == null || path.isBlank() ? "/" : path));
    }
}
