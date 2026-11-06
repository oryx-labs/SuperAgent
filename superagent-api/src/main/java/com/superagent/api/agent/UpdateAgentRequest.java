package com.superagent.api.agent;

import java.util.List;

/**
 * Request to update an Agent's name / character / persona / default model / skills.
 * {@code skillIds} null keeps the current skills (so a rename needn't resend them); [] detaches all.
 * {@code tier} likewise: null keeps it, blank goes back to the system's default tier.
 */
public record UpdateAgentRequest(String name, String persona, String model, String avatar, String character,
                                 List<String> skillIds, String tier) {

    public UpdateAgentRequest(String name, String persona, String model, String avatar, String character, List<String> skillIds) {
        this(name, persona, model, avatar, character, skillIds, null);
    }
}
