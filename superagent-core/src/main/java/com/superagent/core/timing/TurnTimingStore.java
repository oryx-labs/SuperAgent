package com.superagent.core.timing;

import java.util.Collection;
import java.util.List;

/** Persistence SPI for {@link TurnTiming}: one row per chat reply or task turn, keyed by its message id. */
public interface TurnTimingStore {

    /** Inserts or replaces by message id. */
    void save(TurnTiming timing);

    /** Every timing recorded in these sessions (a task's turns live under the task's id), oldest first. */
    List<TurnTiming> forSessions(Collection<String> sessionIds);
}
