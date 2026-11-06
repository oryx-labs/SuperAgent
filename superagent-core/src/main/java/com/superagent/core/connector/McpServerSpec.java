package com.superagent.core.connector;

import java.util.List;
import java.util.Map;

/**
 * One MCP server to hand an agent run, in the shape of Claude's {@code --mcp-config}: an HTTP/SSE {@code url}
 * (with {@code headers}), or a stdio {@code command} with {@code args} and {@code env}. {@code name} becomes the
 * tool prefix ({@code mcp__<name>__…}).
 */
public record McpServerSpec(
        String name,
        ConnectorTransport transport,
        String url,
        Map<String, String> headers,
        String command,
        List<String> args,
        Map<String, String> env) {
}
