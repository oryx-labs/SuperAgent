package com.superagent.api.automation;

import java.time.Instant;

/**
 * One firing. {@code trigger}: schedule | api | manual. {@code status} is the started task's
 * status, or "skipped" when nothing started ({@code skippedReason} says why).
 */
public record AutomationRunDto(
        String id,
        String trigger,
        String taskId,
        String status,
        String skippedReason,
        Instant firedAt) {
}
