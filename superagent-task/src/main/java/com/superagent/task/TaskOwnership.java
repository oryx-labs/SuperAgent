package com.superagent.task;

import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStore;
import com.superagent.core.tenant.CompanyStarted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tasks are visible only to the user they belong to. Tasks from before that rule were filed under the
 * placeholder owner ({@code superagent.task.default-owner}) or none, so no one would see them; on startup
 * they're handed to the initial admin, the same way agents without an owner are.
 */
@Component
class TaskOwnership {

    private static final Logger log = LoggerFactory.getLogger(TaskOwnership.class);

    private final TaskStore tasks;
    private final String placeholderOwner;
    private final String adminUsername;

    TaskOwnership(TaskStore tasks,
                  @Value("${superagent.task.default-owner:local}") String placeholderOwner,
                  @Value("${superagent.auth.admin.username:admin}") String adminUsername) {
        this.tasks = tasks;
        this.placeholderOwner = placeholderOwner;
        this.adminUsername = adminUsername;
    }

    @EventListener(CompanyStarted.class)
    void claimUnownedTasks() {
        List<Task> orphans = tasks.listTasks().stream()
                .filter(t -> t.createdBy() == null || t.createdBy().isBlank() || t.createdBy().equals(placeholderOwner))
                .toList();
        orphans.forEach(t -> tasks.save(t.withCreatedBy(adminUsername)));
        if (!orphans.isEmpty()) {
            log.info("assigned {} task(s) without a real owner to {}", orphans.size(), adminUsername);
        }
    }
}
