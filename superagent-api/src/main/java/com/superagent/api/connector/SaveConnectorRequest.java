package com.superagent.api.connector;

/**
 * Create or update an MCP connector. Enum fields are lowercase strings and all
 * optional on create: {@code type} defaults to optional, {@code authMethod} to
 * system_oauth, {@code transport} to stdio.
 */
public record SaveConnectorRequest(
        String name,
        String description,
        String type,
        String authMethod,
        String transport,
        String endpoint) {
}
