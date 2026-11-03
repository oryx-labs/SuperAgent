package com.superagent.core.chat;

/**
 * Kinds of streamed events an agent emits while producing a reply. These are the
 * normalised event types SuperAgent exposes, independent of any one CLI's wire
 * format.
 */
public enum AgentEventType {

    /** Session/bootstrap info, e.g. the agent's session id becoming known. */
    SYSTEM,

    /** Incremental reasoning / thinking text. */
    THINKING,

    /** Incremental assistant output text. */
    TEXT,

    /** The agent invoked a tool. */
    TOOL_USE,

    /** A tool returned a result. */
    TOOL_RESULT,

    /** Terminal event for a turn: the final result/summary. */
    RESULT,

    /** Cost/usage metadata for a turn (e.g. total_cost_usd); not shown in the output stream. */
    USAGE,

    /** Something failed. */
    ERROR
}
