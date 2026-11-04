package com.superagent.core.task;

/** How a task was created: from a chat conversation, or from a structured task form. */
public enum TaskSource {
    WEB_CHAT,
    TASK_FORM,
    /** Started by an automation's schedule or API trigger. */
    AUTOMATION,
    /** Converted from a to-do the inbox extracted from received messages. */
    INBOX
}
