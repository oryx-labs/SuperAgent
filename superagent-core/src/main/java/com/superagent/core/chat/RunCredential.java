package com.superagent.core.chat;

/**
 * The endpoint and key one CLI run uses, injected per run (environment / {@code -c}, never argv or the CLI's
 * own config files). {@code baseUrl} null = the vendor's default endpoint; {@code bearer} sends the key as an
 * {@code Authorization: Bearer} token (what relays and gateways expect) rather than the vendor's API-key header.
 * {@code id} names the pool entry it came from, so a failure can be reported against it.
 */
public record RunCredential(String id, String baseUrl, String apiKey, boolean bearer) {

    @Override
    public String toString() { // never print the key
        return "RunCredential[" + id + " " + (baseUrl == null ? "default endpoint" : baseUrl) + "]";
    }
}
