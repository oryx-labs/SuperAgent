package com.superagent.core.agent;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for a SuperAgent's work record and its events. The active backend is chosen in superagent-storage. */
public interface SuperAgentWorkStore {

    /** The user's work, most recently assigned first. */
    List<SuperAgentWork> list(String owner);

    Optional<SuperAgentWork> find(String taskId);

    /** Inserts or replaces by task id. */
    SuperAgentWork save(SuperAgentWork work);

    void appendEvent(SuperAgentWorkEvent event);

    /** Oldest first. */
    List<SuperAgentWorkEvent> events(String taskId);
}
