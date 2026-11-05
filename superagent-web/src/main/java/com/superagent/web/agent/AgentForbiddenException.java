package com.superagent.web.agent;

/** A collaborator tried an owner-only action on a shared agent; mapped to HTTP 403. */
public class AgentForbiddenException extends RuntimeException {

    public AgentForbiddenException(String message) {
        super(message);
    }
}
