package com.superagent.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/**
 * Tools a module offers to agents through SuperAgent's own MCP server. Each module registers its own
 * provider (a Spring bean), so the MCP endpoint only speaks the protocol and never depends on the
 * modules behind it. {@link #kinds()} says which runs see the tools: a chat, a task, a SuperAgent round.
 */
public interface McpToolProvider {

    Set<McpGrants.Kind> kinds();

    /** Tool definitions (name, description, inputSchema), built with {@link McpTools}. */
    List<ObjectNode> tools();

    /**
     * Runs one of this provider's tools for the run the grant stands for. Tool-level failures (bad
     * arguments, not found, not allowed) throw {@link McpToolException}; the agent sees them and can retry.
     */
    McpToolResult call(McpGrants.Grant grant, String name, JsonNode args);

    default boolean handles(String name) {
        return tools().stream().anyMatch(t -> name.equals(t.path("name").asText()));
    }
}
