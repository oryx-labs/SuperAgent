package com.superagent.web.task;

import java.util.Set;

/**
 * The narrow seam the watchdog drives. Kept free of any volatile execution types
 * so it is stable while the task execution path evolves. {@link TaskService}
 * implements it.
 */
public interface TaskRecovery {

    /** Task ids this process is actively executing right now (have a live agent run). */
    Set<String> activeTaskIds();

    /**
     * Recovers one task that the store says is RUNNING but which is not actively
     * executing here — interrupted by a restart, or a dead/stalled run. Resumes it on
     * its persisted session so it continues toward completion, rotating to a fresh
     * session after repeated failures, and giving up (marking it ERROR) only once the
     * overall attempt budget is exhausted.
     */
    void recover(String taskId);
}
