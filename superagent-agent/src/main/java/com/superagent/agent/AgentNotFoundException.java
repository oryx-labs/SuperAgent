package com.superagent.agent;

import com.superagent.core.error.NotFoundException;

/** Thrown when an agent id does not resolve; mapped to HTTP 404. */
public class AgentNotFoundException extends NotFoundException {

    public AgentNotFoundException(String agentId) {
        super("No such agent: " + agentId);
    }
}
