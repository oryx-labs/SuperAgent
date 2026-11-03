package com.superagent.core.chat;

/** Lifecycle state of a {@link ChatSession}. DELETED is a soft delete: the row stays, the UI hides it. */
public enum SessionStatus {
    ACTIVE,
    CLOSED,
    DELETED
}
