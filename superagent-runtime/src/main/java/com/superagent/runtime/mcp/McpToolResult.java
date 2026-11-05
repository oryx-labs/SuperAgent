package com.superagent.runtime.mcp;

/** A tool's answer to the agent; {@code error} marks a result the agent should treat as a failure. */
public record McpToolResult(String text, boolean error) {

    public static McpToolResult ok(String text) {
        return new McpToolResult(text, false);
    }
}
