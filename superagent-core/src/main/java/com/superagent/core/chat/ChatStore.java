package com.superagent.core.chat;

import java.util.List;
import java.util.Optional;

/**
 * Persistence SPI for chat state, implemented on MySQL in {@code superagent-storage}.
 * All methods are expected to be thread-safe.
 *
 * <p>The model is append-heavy: messages and events are only ever added, while
 * a session's metadata ({@code agentSessionId}, {@code title}, {@code status})
 * is updated in place via {@link #updateSession(ChatSession)}.
 */
public interface ChatStore {

    // --- sessions ---

    ChatSession createSession(ChatSession session);

    Optional<ChatSession> findSession(String sessionId);

    List<ChatSession> listSessions();

    /** Sessions owned by {@code agentId}, newest first; empty for an unknown agent. */
    List<ChatSession> listSessionsByAgent(String agentId);

    /** Replaces the stored session metadata for {@code session.id()}. */
    void updateSession(ChatSession session);

    // --- messages ---

    ChatMessage appendMessage(ChatMessage message);

    List<ChatMessage> listMessages(String sessionId);

    /** Updates the stored content of one message in place. */
    void updateMessage(String sessionId, String messageId, String content);

    // --- events ---

    void appendEvent(AgentEvent event);

    List<AgentEvent> listEvents(String sessionId);
}
