package com.superagent.api.superagent;

import java.time.Instant;

/**
 * The signed-in user's SuperAgent and where its inbox stands. Inbox messages created after
 * {@code processedUntil} are still waiting; while {@code running}, those up to {@code roundCutoff}
 * are the ones being worked through right now. {@code plan} is the latest plan, or null.
 */
public record SuperAgentStateDto(
        String agentId,
        String sessionId,
        Instant processedUntil,
        boolean running,
        Instant roundCutoff,
        SuperAgentPlanDto plan,
        int teamSize,
        int teamLimit) {
}
