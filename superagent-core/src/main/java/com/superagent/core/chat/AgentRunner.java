package com.superagent.core.chat;

/**
 * Drives one kind of CLI agent (Claude, Codex, Kimi, ...). Implementations live
 * in {@code superagent-worker}; each knows how to invoke its agent, stream the
 * output, and normalise it into {@link AgentEvent}s.
 *
 * <p>SuperAgent is agent-agnostic: adding a new agent means adding a new
 * {@code AgentRunner}, with nothing else changing.
 */
public interface AgentRunner {

    /** The agent type this runner handles. */
    AgentType type();

    /**
     * Runs one turn, blocking until the agent finishes, and emits events to
     * {@code sink} as they arrive. The agent's session id, once known, is
     * emitted as a {@link AgentEventType#SYSTEM} event so the caller can persist
     * it for resuming later.
     *
     * @param request      the turn to run
     * @param sink         receives streamed events
     * @param cancellation handle the runner registers an abort action on, so the
     *                     turn can be stopped early (e.g. user hit stop)
     */
    void run(AgentRunRequest request, AgentEventSink sink, Cancellation cancellation);
}
