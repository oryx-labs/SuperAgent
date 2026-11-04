package com.superagent.web.knowledge;

/** The destination name is already taken in that folder; mapped to HTTP 409. */
public class KnowledgeConflictException extends RuntimeException {

    public KnowledgeConflictException(String name) {
        super("An item named \"" + name + "\" already exists here");
    }
}
