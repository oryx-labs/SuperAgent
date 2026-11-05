package com.superagent.task.automation;

import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.task.TaskService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** The two things automations need from the task system: start a task, and read a task's status. */
@Component
class AutomationTasks {

    private final TaskService taskService;
    private final TaskStore tasks;

    AutomationTasks(TaskService taskService, TaskStore tasks) {
        this.taskService = taskService;
        this.tasks = tasks;
    }

    /** Starts a task owned by {@code owner}; returns its id. */
    String start(CreateTaskRequest request, String owner) {
        return taskService.create(request, owner).id();
    }

    Optional<TaskStatus> status(String taskId) {
        return tasks.findTask(taskId).map(Task::status);
    }
}
