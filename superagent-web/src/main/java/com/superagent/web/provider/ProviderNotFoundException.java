package com.superagent.web.provider;

/** Thrown when a provider id does not resolve; mapped to HTTP 404. */
public class ProviderNotFoundException extends RuntimeException {
    public ProviderNotFoundException(String id) {
        super("No such provider: " + id);
    }
}
