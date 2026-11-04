package com.superagent.core.automation;

import java.time.Instant;

/**
 * One firing of an automation. {@code trigger}: schedule | api | manual. {@code taskId} is the
 * task it started, or null when nothing started ({@code skippedReason} says why, e.g. the
 * previous run was still going); the run's status is that task's status.
 */
public record AutomationRun(
        String id,
        String automationId,
        String trigger,
        String taskId,
        String skippedReason,
        Instant firedAt) {
}
