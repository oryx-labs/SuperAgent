package com.superagent.core.connector;

import java.util.Optional;

/** Persistence SPI for per-connector secret credentials. Keyed by connector id. */
public interface ConnectorCredentialStore {

    Optional<ConnectorCredential> find(String connectorId);

    /** Inserts or replaces the credential for its connector. */
    ConnectorCredential save(ConnectorCredential credential);

    void delete(String connectorId);
}
