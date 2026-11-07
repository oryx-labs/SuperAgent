package com.superagent.core.task;

import java.util.List;

/** The model decisions of each task, oldest first. */
public interface TaskModelChoiceStore {

    void save(TaskModelChoice choice);

    List<TaskModelChoice> list(String taskId);
}
