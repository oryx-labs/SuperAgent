package com.superagent.api.task;

/**
 * Request to create a task. Only {@code title} and {@code brief} (the spec the
 * agent must carry out) are required; the rest is optional metadata. {@code source}
 * defaults to task_form, {@code agentType} to claude. {@code kind} is the task type
 * (coding/writing/research/stats/general); blank means auto-classify from the brief.
 */
public record CreateTaskRequest(
        String title,
        String brief,
        String source,
        String repo,
        String branch,
        String flow,
        String model,
        String agentType,
        String kind,
        /** When true, the agent saves the task's main output to the creator's knowledge base. */
        Boolean saveToKnowledge,
        /** Target folder for that save; blank = let the agent pick (or create) a fitting one. */
        String knowledgeFolder) {

    public CreateTaskRequest(String title, String brief, String source, String repo, String branch, String flow,
                             String model, String agentType, String kind) {
        this(title, brief, source, repo, branch, flow, model, agentType, kind, null, null);
    }
}
