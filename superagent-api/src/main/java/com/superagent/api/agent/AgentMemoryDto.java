package com.superagent.api.agent;

import java.time.Instant;

/**
 * An agent's long-term memory as its side panel shows it. {@code memory} is Markdown (null = nothing yet);
 * {@code canEdit} only for the agent's owner; {@code distilling} while a run is folding new conversation in.
 */
public record AgentMemoryDto(String memory, Instant updatedAt, boolean canEdit, boolean distilling) {
}
