package com.superagent.api.agent;

import java.util.List;

/** Request to create an Agent; only {@code name} is required. {@code character} is a built-in key or null for custom. */
public record CreateAgentRequest(String name, String persona, String model, String avatar, String character,
                                 List<String> skillIds) {
}
