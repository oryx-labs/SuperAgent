package com.superagent.runtime.mcp;

/** A tool-level failure the agent should see (and can fix and retry); not a protocol error. */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }
}
