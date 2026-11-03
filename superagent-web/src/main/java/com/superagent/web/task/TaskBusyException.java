package com.superagent.web.task;

/** Thrown when an operation needs an idle task but the task is still running; mapped to HTTP 409. */
public class TaskBusyException extends RuntimeException {

    public TaskBusyException(String id) {
        super("Task is running: " + id);
    }
}
