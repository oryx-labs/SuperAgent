package com.superagent.api.agent;

import java.util.List;

/**
 * Request to update an Agent's name / character / persona / default model / skills.
 * {@code skillIds} null keeps the current skills (so a rename needn't resend them); [] detaches all.
 */
public record UpdateAgentRequest(String name, String persona, String model, String avatar, String character,
                                 List<String> skillIds) {
}
