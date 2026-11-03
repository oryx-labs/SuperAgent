package com.superagent.web.agent;

/** Thrown when an agent id does not resolve; mapped to HTTP 404. */
public class AgentNotFoundException extends RuntimeException {

    public AgentNotFoundException(String agentId) {
        super("No such agent: " + agentId);
    }
}
