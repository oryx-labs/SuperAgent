package com.superagent.core.agent;

import java.time.Instant;

/**
 * An Agent (persona): a named role with free-text {@code persona} describing its
 * character and responsibilities, which the user commands through the ordinary
 * chat form. {@code model} is an optional per-agent default model; {@code avatar} is
 * the emoji shown for it in lists.
 *
 * <p>Phase 1 is strictly one agent to one {@link com.superagent.core.chat.ChatSession}
 * (a session carries {@code agentId}); the storage/SPI already models "a session
 * belongs to an agent" so phase 2 can attach many sessions without a rewrite.
 *
 * <p>Unrelated to {@link com.superagent.core.chat.AgentType}, which is the
 * underlying engine (Claude CLI). Mutation goes through the {@code with*} helpers.
 */
public record Agent(
        String id,
        String name,
        String avatar,
        String persona,
        String model,
        AgentStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public Agent withName(String newName) {
        return new Agent(id, newName, avatar, persona, model, status, createdAt, Instant.now());
    }

    public Agent withAvatar(String newAvatar) {
        return new Agent(id, name, newAvatar, persona, model, status, createdAt, Instant.now());
    }

    public Agent withPersona(String newPersona) {
        return new Agent(id, name, avatar, newPersona, model, status, createdAt, Instant.now());
    }

    public Agent withModel(String newModel) {
        return new Agent(id, name, avatar, persona, newModel, status, createdAt, Instant.now());
    }

    /** Soft delete: marks the agent DELETED so it drops out of listings; the row is kept. */
    public Agent deleted() {
        return new Agent(id, name, avatar, persona, model, AgentStatus.DELETED, createdAt, Instant.now());
    }

    public Agent touched() {
        return new Agent(id, name, avatar, persona, model, status, createdAt, Instant.now());
    }
}
