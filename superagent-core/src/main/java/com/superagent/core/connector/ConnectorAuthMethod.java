package com.superagent.core.connector;

/**
 * How an MCP connector authenticates. {@code SHARED_TOKEN} is one token the administrator enters for the
 * whole company; {@code PERSONAL_TOKEN} and {@code OAUTH} are per employee (each authorizes with their own
 * account), so an agent acts with the identity of whoever it is working for.
 */
public enum ConnectorAuthMethod {
    NONE,
    SHARED_TOKEN,
    PERSONAL_TOKEN,
    OAUTH;

    public boolean personal() {
        return this == PERSONAL_TOKEN || this == OAUTH;
    }
}
