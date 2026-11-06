package com.superagent.core.connector;

/**
 * What a connector links the company to. An MCP server gives agents tools (outward); Feishu delivers
 * messages to each employee's SuperAgent and lets it message them back (inward).
 */
public enum ConnectorKind {
    MCP,
    FEISHU
}
