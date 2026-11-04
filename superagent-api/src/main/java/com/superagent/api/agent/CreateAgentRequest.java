package com.superagent.api.agent;

/** Request to create an Agent; only {@code name} is required, {@code persona}/{@code model} optional. */
public record CreateAgentRequest(String name, String persona, String model, String avatar) {
}
