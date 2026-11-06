package com.superagent.core.connector;

/** Published when an administrator adds, edits, enables or removes a connector, so live connections follow. */
public record ConnectorChanged(String connectorId) {
}
