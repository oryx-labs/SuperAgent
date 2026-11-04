package com.superagent.core.agent;

import java.time.Instant;

/**
 * One piece of work a SuperAgent handed out: a task it assigned to a team member, keyed by that
 * task's id. {@code status} is the last status the SuperAgent saw and reported — {@code running},
 * {@code needs_confirmation} (still open), or {@code done} / {@code error} / {@code stopped} /
 * {@code deleted} (finished). {@code summary} is a short snapshot of the outcome; the task has the rest.
 * {@code planId} is null for work recorded before plans were tracked (back-filled from old tasks).
 */
public record SuperAgentWork(
        String taskId,
        String owner,
        String planId,
        String title,
        String memberId,
        String memberName,
        String status,
        String summary,
        Instant assignedAt,
        Instant finishedAt,
        Instant updatedAt) {

    public static final String RUNNING = "running";
    public static final String NEEDS_CONFIRMATION = "needs_confirmation";

    public boolean open() {
        return RUNNING.equals(status) || NEEDS_CONFIRMATION.equals(status);
    }

    public SuperAgentWork withStatus(String newStatus, String newSummary, Instant finished) {
        return new SuperAgentWork(taskId, owner, planId, title, memberId, memberName, newStatus,
                newSummary != null ? newSummary : summary, assignedAt, finished, Instant.now());
    }
}
