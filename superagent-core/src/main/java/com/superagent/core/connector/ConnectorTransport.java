package com.superagent.core.connector;

/** MCP transport: a local stdio subprocess, or a remote HTTP/SSE endpoint. */
public enum ConnectorTransport {
    STDIO,
    HTTP,
    SSE
}
