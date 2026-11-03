package com.superagent.web.connector;

/** Thrown when a connector id does not resolve; mapped to HTTP 404. */
public class ConnectorNotFoundException extends RuntimeException {

    public ConnectorNotFoundException(String id) {
        super("No such connector: " + id);
    }
}
