package com.superagent.core.task;

import java.util.List;
import java.util.Map;

/** Topic tags on tasks, tagged automatically when a task is done and editable by its owner. */
public interface TaskTagStore {

    /** Replaces the task's tags (an empty list clears them). */
    void set(String taskId, List<String> tags);

    List<String> tags(String taskId);

    /** Every tagged task's tags. */
    Map<String, List<String>> all();
}
