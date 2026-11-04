package com.superagent.core.agent;

import java.time.Instant;

/**
 * Per-user working state of a SuperAgent, so its inbox loop survives restarts.
 *
 * <p>{@code processedUntil}: inbox messages created at or before this have been worked through.
 * {@code planId}/{@code planStatus}/{@code planJson}: the latest plan it proposed — status
 * {@code proposed} (waiting for the user), {@code approved} (it may hire and assign), or null when
 * there is none; the JSON is the plan's shape as the web layer defines it. {@code trackedJson} is no
 * longer written — assigned tasks are followed in {@link SuperAgentWork} — and is kept only because
 * existing rows have the column.
 */
public record SuperAgentState(
        String owner,
        String agentId,
        Instant processedUntil,
        String planId,
        String planStatus,
        String planJson,
        String trackedJson,
        Instant updatedAt) {

    public static final String PLAN_PROPOSED = "proposed";
    public static final String PLAN_APPROVED = "approved";

    public SuperAgentState processedTo(Instant until) {
        return new SuperAgentState(owner, agentId, until, planId, planStatus, planJson, trackedJson, Instant.now());
    }

    public SuperAgentState withPlan(String id, String status, String json) {
        return new SuperAgentState(owner, agentId, processedUntil, id, status, json, trackedJson, Instant.now());
    }
}
