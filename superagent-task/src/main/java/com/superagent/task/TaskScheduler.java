package com.superagent.task;

import com.superagent.platform.tenant.CompanyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs new tasks. Creating a task only records it; this picks waiting tasks up in the order they were made and
 * hands each to {@link TaskService#admit}, which chooses its model and gets it a session. Woken at once when a task
 * is recorded, and looks anyway every {@code sweep-seconds}, so nothing waits forever (after a restart, say).
 * A complex task's subtasks are held back to its parallel limit, and a complex task whose subtasks have all finished
 * is set to sum them up.
 */
@Component
class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private final TaskService tasks;
    private final long sweepSeconds;
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running = true;

    private final CompanyService companies;

    TaskScheduler(CompanyService companies, TaskService tasks, @Value("${superagent.task.scheduler.sweep-seconds:30}") long sweepSeconds) {
        this.companies = companies;
        this.tasks = tasks;
        this.sweepSeconds = Math.max(1, sweepSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        tasks.useScheduler(wake::release);
        Thread t = new Thread(this::loop, "task-scheduler");
        t.setDaemon(true);
        t.start();
        log.info("task scheduler started (sweep every {}s)", sweepSeconds);
    }

    private void loop() {
        while (running) {
            try {
                companies.forEach(() -> {
                    tasks.sumUpFinished(); // complex tasks whose subtasks are all done carry on
                    for (var task : tasks.awaitingAdmission()) {
                        tasks.admit(task);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("task scheduler pass failed", e);
            }
            try {
                wake.tryAcquire(sweepSeconds, TimeUnit.SECONDS);
                wake.drainPermits();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        running = false;
        wake.release();
    }
}
