package com.superagent.core.agent;

import java.time.Instant;

/**
 * An Agent (persona): a named role whose {@code character} is a built-in preset key from
 * {@link AgentCharacters} (null = custom) and whose free-text {@code persona} is the whole
 * character when custom, or extra instructions on top of a preset. The user commands it
 * through the ordinary chat form. {@code model} is an optional per-agent default model; {@code avatar} is
 * the emoji shown for it in lists.
 *
 * <p>Every agent belongs to one user ({@code owner}, a username); only they see and use it.
 * {@code kind} marks the user's built-in {@link AgentKind#SUPER SuperAgent}; {@code managedBy}
 * is the id of the SuperAgent that created this agent as a team member (null = made by the user).
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
        String character,
        String persona,
        String model,
        AgentStatus status,
        String owner,
        AgentKind kind,
        String managedBy,
        Instant createdAt,
        Instant updatedAt,
        /** The tier chosen when the admin has turned tiers on (null = the default tier). */
        String tier) {

    public Agent(String id, String name, String avatar, String character, String persona, String model, AgentStatus status,
                 String owner, AgentKind kind, String managedBy, Instant createdAt, Instant updatedAt) {
        this(id, name, avatar, character, persona, model, status, owner, kind, managedBy, createdAt, updatedAt, null);
    }

    public Agent withTier(String newTier) {
        return new Agent(id, name, avatar, character, persona, model, status, owner, kind, managedBy, createdAt, updatedAt, newTier);
    }


    public Agent {
        kind = kind == null ? AgentKind.STANDARD : kind;
    }

    public boolean isSuper() {
        return kind == AgentKind.SUPER;
    }

    public Agent withName(String newName) {
        return new Agent(id, newName, avatar, character, persona, model, status, owner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }

    public Agent withAvatar(String newAvatar) {
        return new Agent(id, name, newAvatar, character, persona, model, status, owner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }

    public Agent withPersona(String newPersona) {
        return new Agent(id, name, avatar, character, newPersona, model, status, owner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }

    public Agent withModel(String newModel) {
        return new Agent(id, name, avatar, character, persona, newModel, status, owner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }

    public Agent withOwner(String newOwner) {
        return new Agent(id, name, avatar, character, persona, model, status, newOwner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }

    /** Soft delete: marks the agent DELETED so it drops out of listings; the row is kept. */
    public Agent deleted() {
        return new Agent(id, name, avatar, character, persona, model, AgentStatus.DELETED, owner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }

    public Agent touched() {
        return new Agent(id, name, avatar, character, persona, model, status, owner, kind, managedBy,
                createdAt, Instant.now(), tier);
    }
}
