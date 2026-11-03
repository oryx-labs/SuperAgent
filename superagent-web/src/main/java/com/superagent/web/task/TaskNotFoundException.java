package com.superagent.web.task;

/** Thrown when a task id does not resolve; mapped to HTTP 404. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String id) {
        super("No such task: " + id);
    }
}
