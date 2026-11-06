package com.superagent.core.connector;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for connector secrets, keyed by connector id + owner ({@link ConnectorCredential#COMPANY} for the company's). */
public interface ConnectorCredentialStore {

    Optional<ConnectorCredential> find(String connectorId, String owner);

    List<ConnectorCredential> list(String connectorId);

    /** Inserts or replaces by connector id + owner. */
    ConnectorCredential save(ConnectorCredential credential);

    void delete(String connectorId, String owner);

    void deleteAll(String connectorId);
}
