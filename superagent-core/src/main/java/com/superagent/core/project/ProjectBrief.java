package com.superagent.core.project;

import java.time.Instant;

/** What everyone in a project works from: its goal, background, norms and glossary. Edited by the owner. */
public record ProjectBrief(
        String projectId,
        String goal,
        String background,
        String norms,
        String glossary,
        String updatedBy,
        Instant updatedAt) {

    public boolean empty() {
        return blank(goal) && blank(background) && blank(norms) && blank(glossary);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
