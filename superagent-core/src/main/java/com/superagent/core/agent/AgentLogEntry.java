package com.superagent.core.agent;

import java.time.Instant;

/**
 * One line of an agent's run log: what happened underneath the conversation — sessions starting and
 * being replaced, turns succeeding or failing, memory being distilled, a SuperAgent planning and assigning.
 * {@code level}: info | warn | error. {@code category}: session | turn | memory | superagent.
 * {@code detail} is optional extra text shown when the line is expanded.
 */
public record AgentLogEntry(
        String id,
        String agentId,
        String owner,
        Instant at,
        String level,
        String category,
        String summary,
        String detail) {
}
