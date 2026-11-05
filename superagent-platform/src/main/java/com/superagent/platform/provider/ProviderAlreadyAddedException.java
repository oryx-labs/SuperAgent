package com.superagent.platform.provider;

import com.superagent.core.error.ConflictException;

/** Thrown when adding a catalogue entry that is already managed; mapped to HTTP 409. */
public class ProviderAlreadyAddedException extends ConflictException {
    public ProviderAlreadyAddedException(String key) {
        super("Provider already added: " + key);
    }
}
