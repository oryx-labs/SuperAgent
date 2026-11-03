package com.superagent.api.agent;

/** Request to update an Agent's name / persona / default model. */
public record UpdateAgentRequest(String name, String persona, String model) {
}
