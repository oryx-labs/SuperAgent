package com.superagent.core.agent;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for {@link SuperAgentState}, one row per user. The active backend is chosen in superagent-storage. */
public interface SuperAgentStateStore {

    List<SuperAgentState> list();

    Optional<SuperAgentState> find(String owner);

    /** Inserts or replaces by owner. */
    SuperAgentState save(SuperAgentState state);
}
