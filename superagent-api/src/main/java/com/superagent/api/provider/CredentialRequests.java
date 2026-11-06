package com.superagent.api.provider;

import java.util.List;

/** Request bodies for a CLI's key pool. */
public final class CredentialRequests {

    private CredentialRequests() {
    }

    /** {@code apiKey} blank on an update = keep the stored key. {@code baseUrl} blank = the vendor's default endpoint. */
    public record Save(String label, String baseUrl, String apiKey, Boolean bearer, Boolean enabled) {
    }

    /** The pool's entries in their new priority order (first = tried first). */
    public record Order(List<String> ids) {
    }
}
