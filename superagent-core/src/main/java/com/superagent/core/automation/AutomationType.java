package com.superagent.core.automation;

/** How an automation is triggered. */
public enum AutomationType {
    /** On a cron schedule, until paused. */
    RECURRING,
    /** Once at a fixed time, then it disables itself. */
    ONCE,
    /** Whenever an external system calls its trigger endpoint with its token. */
    API
}
