package com.superagent.core.connector;

/** How a connector authenticates: a shared system OAuth app, or a per-user token. */
public enum ConnectorAuthMethod {
    SYSTEM_OAUTH,
    PERSONAL_TOKEN
}
