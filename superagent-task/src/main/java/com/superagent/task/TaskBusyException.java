package com.superagent.task;

import com.superagent.core.error.ConflictException;

/** Thrown when an operation needs an idle task but the task is still running; mapped to HTTP 409. */
public class TaskBusyException extends ConflictException {

    public TaskBusyException(String id) {
        super("Task is running: " + id);
    }
}
