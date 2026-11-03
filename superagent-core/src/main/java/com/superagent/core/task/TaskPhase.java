package com.superagent.core.task;

/**
 * One step of a multi-phase task flow. {@code content} holds a phase's produced
 * document (the PRD markdown for the PRD step); {@code evidence} holds verification
 * output (the acceptance step). Both are null until the phase produces them.
 */
public record TaskPhase(
        String key,
        String name,
        TaskPhaseStatus status,
        String content,
        String evidence) {

    public TaskPhase withStatus(TaskPhaseStatus newStatus) {
        return new TaskPhase(key, name, newStatus, content, evidence);
    }

    public TaskPhase withContent(String newContent) {
        return new TaskPhase(key, name, status, newContent, evidence);
    }

    public TaskPhase withEvidence(String newEvidence) {
        return new TaskPhase(key, name, status, content, newEvidence);
    }
}
