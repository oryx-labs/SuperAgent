package com.superagent.knowledge;

import com.superagent.core.error.ForbiddenException;

/** A write to a knowledge base the user may only read; mapped to HTTP 403. */
public class KnowledgeReadOnlyException extends ForbiddenException {

    public KnowledgeReadOnlyException(String message) {
        super(message);
    }
}
