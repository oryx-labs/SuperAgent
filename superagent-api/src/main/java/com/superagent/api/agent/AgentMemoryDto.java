package com.superagent.api.agent;

import java.time.Instant;
import java.util.List;

/**
 * An agent's long-term memory as its side panel shows it. {@code memory} is Markdown in {@code ## category} sections
 * (null = nothing yet); {@code canEdit} only for the agent's owner; {@code distilling} while a run is folding new
 * conversation in. {@code agentSections}: categories the agent wrote itself; {@code agentWrite}: its last such write.
 */
public record AgentMemoryDto(String memory, Instant updatedAt, boolean canEdit, boolean distilling,
                             List<String> agentSections, AgentWrite agentWrite) {

    /** For whom ({@code by}), in which conversation or task, and when the agent last wrote its memory. */
    public record AgentWrite(String by, String byName, String sessionId, String taskId, Instant at) {
    }
}
