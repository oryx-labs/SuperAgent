package com.superagent.core.connector;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for MCP connectors. The active backend is chosen in superagent-storage. */
public interface ConnectorStore {

    List<Connector> listConnectors();

    Optional<Connector> findConnector(String id);

    /** Inserts or replaces the connector by id. */
    Connector save(Connector connector);

    void delete(String id);
}
