package com.superagent.platform.connector;

import com.superagent.core.error.NotFoundException;

/** Thrown when a connector id does not resolve; mapped to HTTP 404. */
public class ConnectorNotFoundException extends NotFoundException {

    public ConnectorNotFoundException(String id) {
        super("No such connector: " + id);
    }
}
