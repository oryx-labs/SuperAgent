package com.superagent.core.automation;

import java.util.List;
import java.util.Optional;

/** Persistence SPI for automations and their run history. The active backend is chosen in superagent-storage. */
public interface AutomationStore {

    List<Automation> list();

    Optional<Automation> find(String id);

    /** Inserts or replaces by id. */
    Automation save(Automation automation);

    /** Deletes the automation; its run history and the tasks it started are kept. */
    void delete(String id);

    void appendRun(AutomationRun run);

    /** Most recent first, at most {@code limit}. */
    List<AutomationRun> listRuns(String automationId, int limit);

    /** The run that started this task, if an automation started it. */
    Optional<AutomationRun> findRunByTask(String taskId);
}
