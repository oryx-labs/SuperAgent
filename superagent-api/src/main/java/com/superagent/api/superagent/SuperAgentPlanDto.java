package com.superagent.api.superagent;

import java.time.Instant;
import java.util.List;

/**
 * A plan the SuperAgent proposed: what it understood, who on the team does what. Nothing is
 * hired or assigned until the user approves it. {@code status}: proposed | approved.
 */
public record SuperAgentPlanDto(
        String id,
        String status,
        String summary,
        List<Member> members,
        List<Assignment> tasks,
        Instant proposedAt) {

    /** {@code agentId} set = an existing team member reused; null = someone to hire. */
    public record Member(String agentId, String name, String specialty) {
    }

    /** {@code assignee} is a member's name from {@link #members}. */
    public record Assignment(String title, String assignee, String brief) {
    }
}
