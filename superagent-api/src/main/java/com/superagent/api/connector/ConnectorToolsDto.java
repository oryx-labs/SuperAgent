package com.superagent.api.connector;

import java.time.Instant;
import java.util.List;

/**
 * What a connector lets agents do, as its MCP server lists it ({@code tools/list}) under the viewer's own
 * authorization. {@code error} says why the list couldn't be read; {@code note} explains an empty list.
 */
public record ConnectorToolsDto(String connectorId, List<Tool> tools, Instant fetchedAt, String error, String note) {

    /** {@code readOnly} / {@code destructive} are the server's own hints, null when it gave none. */
    public record Tool(String name, String title, String description, Boolean readOnly, Boolean destructive,
                       List<Param> params) {
    }

    public record Param(String name, String type, boolean required, String description) {
    }
}
