package com.superagent.task;

import com.superagent.core.error.NotFoundException;

/** Thrown when a task id does not resolve; mapped to HTTP 404. */
public class TaskNotFoundException extends NotFoundException {

    public TaskNotFoundException(String id) {
        super("No such task: " + id);
    }
}
