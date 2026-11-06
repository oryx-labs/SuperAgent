package com.superagent.platform.provider;

import java.io.IOException;

/**
 * An inference check that never reached the model: the API, gateway or the CLI's cloud service couldn't be
 * contacted. It says nothing about the model itself, so the model keeps its last known status.
 */
public class ProbeUnreachableException extends IOException {

    public ProbeUnreachableException(String message) {
        super(message);
    }
}
