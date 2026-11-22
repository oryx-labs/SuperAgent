package com.superagent.api.agent;

import java.util.List;

/** One sidebar group's agent ids in the order the user dragged them to. */
public record AgentOrderRequest(List<String> agentIds) {
}
