package com.superagent.agent;

import com.superagent.core.error.ForbiddenException;

/** A collaborator tried an owner-only action on a shared agent; mapped to HTTP 403. */
public class AgentForbiddenException extends ForbiddenException {

    public AgentForbiddenException(String message) {
        super(message);
    }
}
