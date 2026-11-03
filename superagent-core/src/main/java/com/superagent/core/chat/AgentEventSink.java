package com.superagent.core.chat;

/**
 * Receives normalised events as an agent produces them. The orchestrator
 * supplies a sink that forwards each event to the HTTP stream (SSE) and persists
 * it; the {@link AgentRunner} calls {@link #emit(AgentEvent)} for every event.
 *
 * <p>Implementations should be cheap and non-blocking where possible, since they
 * are called on the agent's output-reading thread.
 */
@FunctionalInterface
public interface AgentEventSink {

    void emit(AgentEvent event);
}
