package com.superagent.api.handover;

import java.util.List;

/** What would move if the admin confirms the handover. */
public record HandoverPreviewDto(
        String fromUserId,
        String fromUsername,
        String toUserId,
        String toUsername,
        int agents,
        int superAgents,
        TaskCounts tasks,
        int automations,
        int files,
        int folders,
        int projects,
        int personalSkills,
        int openSuperAgentWork,
        List<String> warnings) {

    public record TaskCounts(int running, int pending, int waiting, int needsConfirmation, int terminal) {
        public int active() {
            return running + pending + waiting + needsConfirmation;
        }
    }
}
