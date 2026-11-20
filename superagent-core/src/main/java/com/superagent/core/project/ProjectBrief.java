package com.superagent.core.project;

import java.time.Instant;

/**
 * What everyone in a project works from: its goal, background, norms and glossary. Edited by the owner, by hand or
 * through an agent acting for them: then {@code agentId} and the conversation ({@code sessionId}) or task it was in.
 */
public record ProjectBrief(
        String projectId,
        String goal,
        String background,
        String norms,
        String glossary,
        String updatedBy,
        Instant updatedAt,
        String agentId,
        String sessionId,
        String taskId) {

    public ProjectBrief(String projectId, String goal, String background, String norms, String glossary, String updatedBy,
                        Instant updatedAt) {
        this(projectId, goal, background, norms, glossary, updatedBy, updatedAt, null, null, null);
    }

    public boolean empty() {
        return blank(goal) && blank(background) && blank(norms) && blank(glossary);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
