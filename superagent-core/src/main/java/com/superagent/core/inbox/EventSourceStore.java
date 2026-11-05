package com.superagent.core.inbox;

import java.util.List;
import java.util.Optional;

public interface EventSourceStore {

    Optional<EventSource> find(String owner, String type);

    List<EventSource> listEnabled(String type);

    /** Every source, enabled or not. */
    List<EventSource> list();

    /** Insert or replace by owner + type. */
    void save(EventSource source);
}
