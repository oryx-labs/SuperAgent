package com.superagent.api.handover;

import java.time.Instant;
import java.util.List;

/** Outcome of an executed handover. */
public record HandoverResultDto(
        String handoverId,
        String status,
        String fromUsername,
        String toUsername,
        boolean fromUserDisabled,
        Counts counts,
        List<String> warnings,
        Instant finishedAt) {

    public record Counts(
            int agents,
            int tasksStopped,
            int tasksReassigned,
            int automations,
            int files,
            int folders,
            int projects,
            int personalSkills,
            int openSuperAgentWork) {
    }
}
