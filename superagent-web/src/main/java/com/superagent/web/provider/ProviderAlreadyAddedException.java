package com.superagent.web.provider;

/** Thrown when adding a catalogue entry that is already managed; mapped to HTTP 409. */
public class ProviderAlreadyAddedException extends RuntimeException {
    public ProviderAlreadyAddedException(String key) {
        super("Provider already added: " + key);
    }
}
