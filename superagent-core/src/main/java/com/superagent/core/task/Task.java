package com.superagent.core.task;

import com.superagent.core.chat.AgentType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A document-driven agent run with a lifecycle and delivery. The {@code brief} is
 * the spec the agent must understand, execute, and verify; its execution stream
 * and steering messages are stored under {@link #id()} in the shared agent-run
 * tables, so the detail view replays exactly how the work was produced.
 *
 * <p>{@code spend} accumulates estimated cost; {@code progress} is 0–100.
 * Timestamps are UTC. Mutation goes through the {@code with*} helpers so callers
 * never assemble a half-updated record by hand.
 */
public record Task(
        String id,
        String title,
        String brief,
        TaskSource source,
        String repo,
        String branch,
        String flow,
        TaskStatus status,
        int progress,
        BigDecimal spend,
        String model,
        AgentType agentType,
        String workspaceDir,
        String createdBy,
        String error,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        /** The tier chosen when the admin has turned tiers on (null = the default tier). */
        String tier) {

    public Task(String id, String title, String brief, TaskSource source, String repo, String branch, String flow,
                TaskStatus status, int progress, BigDecimal spend, String model, AgentType agentType, String workspaceDir,
                String createdBy, String error, Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {
        this(id, title, brief, source, repo, branch, flow, status, progress, spend, model, agentType, workspaceDir,
                createdBy, error, createdAt, startedAt, finishedAt, updatedAt, null);
    }

    public Task withTier(String newTier) {
        return new Task(id, title, brief, source, repo, branch, flow, status, progress, spend, model, agentType, workspaceDir,
                createdBy, error, createdAt, startedAt, finishedAt, updatedAt, newTier);
    }


    public Task withCreatedBy(String owner) {
        return new Task(id, title, brief, source, repo, branch, flow, status, progress, spend,
                model, agentType, workspaceDir, owner, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    public Task withStatus(TaskStatus newStatus) {
        return new Task(id, title, brief, source, repo, branch, flow, newStatus, progress, spend,
                model, agentType, workspaceDir, createdBy, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    public Task withProgress(int newProgress) {
        return new Task(id, title, brief, source, repo, branch, flow, status, clamp(newProgress), spend,
                model, agentType, workspaceDir, createdBy, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    public Task withSpend(BigDecimal newSpend) {
        return new Task(id, title, brief, source, repo, branch, flow, status, progress, newSpend,
                model, agentType, workspaceDir, createdBy, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    public Task withWorkspaceDir(String newWorkspaceDir) {
        return new Task(id, title, brief, source, repo, branch, flow, status, progress, spend,
                model, agentType, newWorkspaceDir, createdBy, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    public Task started() {
        Instant now = Instant.now();
        return new Task(id, title, brief, source, repo, branch, flow, TaskStatus.RUNNING, progress, spend,
                model, agentType, workspaceDir, createdBy, error, createdAt, now, finishedAt, now, tier);
    }

    /** Settles the task at a terminal status, stamping {@code finishedAt} and final progress. */
    public Task finished(TaskStatus terminal, int finalProgress, String errorOrNull) {
        Instant now = Instant.now();
        return new Task(id, title, brief, source, repo, branch, flow, terminal, clamp(finalProgress), spend,
                model, agentType, workspaceDir, createdBy, errorOrNull, createdAt, startedAt, now, now, tier);
    }

    public Task withTitle(String newTitle) {
        return new Task(id, newTitle, brief, source, repo, branch, flow, status, progress, spend,
                model, agentType, workspaceDir, createdBy, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    public Task touched() {
        return new Task(id, title, brief, source, repo, branch, flow, status, progress, spend,
                model, agentType, workspaceDir, createdBy, error, createdAt, startedAt, finishedAt, Instant.now(), tier);
    }

    private static int clamp(int p) {
        return Math.max(0, Math.min(100, p));
    }
}
