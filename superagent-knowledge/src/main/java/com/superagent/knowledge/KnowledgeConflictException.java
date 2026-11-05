package com.superagent.knowledge;

import com.superagent.core.error.ConflictException;

/** The destination name is already taken in that folder; mapped to HTTP 409. */
public class KnowledgeConflictException extends ConflictException {

    public KnowledgeConflictException(String name) {
        super("An item named \"" + name + "\" already exists here");
    }
}
