package com.superagent.platform.provider;

import com.superagent.core.error.NotFoundException;

/** Thrown when a provider id does not resolve; mapped to HTTP 404. */
public class ProviderNotFoundException extends NotFoundException {
    public ProviderNotFoundException(String id) {
        super("No such provider: " + id);
    }
}
