package com.superagent.web.task;

import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps running tasks alive across failures and restarts. It never runs agents
 * itself — it only decides <em>which</em> tasks need rescuing and hands them to
 * {@link TaskRecovery}, which owns the actual resume/rotate logic.
 *
 * <ul>
 *   <li><b>On startup</b> (after the context is ready): every task the store still
 *       marks RUNNING was interrupted by the restart (nothing is executing yet), so
 *       each is recovered — i.e. resumed so it continues to completion.</li>
 *   <li><b>Periodically</b>: a task that is RUNNING but is not actively executing
 *       here and hasn't advanced for {@code stall-seconds} is treated as dead/stalled
 *       and recovered.</li>
 * </ul>
 *
 * The {@link TaskRecovery} dependency is optional: until the execution layer
 * provides one, the watchdog stays dormant rather than failing startup.
 */
@Component
public class TaskWatchdog {

    private static final Logger log = LoggerFactory.getLogger(TaskWatchdog.class);

    private final TaskStore tasks;
    private final ObjectProvider<TaskRecovery> recoveryProvider;
    private final boolean enabled;
    private final long intervalSeconds;
    private final Duration stall;
    private volatile TaskRecovery recovery;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-watchdog");
        t.setDaemon(true);
        return t;
    });

    public TaskWatchdog(
            TaskStore tasks,
            ObjectProvider<TaskRecovery> recoveryProvider,
            @Value("${superagent.task.watchdog.enabled:true}") boolean enabled,
            @Value("${superagent.task.watchdog.interval-seconds:30}") long intervalSeconds,
            @Value("${superagent.task.watchdog.stall-seconds:120}") long stallSeconds) {
        this.tasks = tasks;
        this.recoveryProvider = recoveryProvider;
        this.enabled = enabled;
        this.intervalSeconds = Math.max(5, intervalSeconds);
        this.stall = Duration.ofSeconds(Math.max(30, stallSeconds));
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled) {
            log.info("task watchdog disabled");
            return;
        }
        this.recovery = recoveryProvider.getIfAvailable();
        if (recovery == null) {
            log.info("task watchdog dormant: no TaskRecovery wired yet");
            return;
        }
        recoverInterruptedAfterRestart();
        scheduler.scheduleWithFixedDelay(this::scanSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("task watchdog started (interval={}s, stall={}s)", intervalSeconds, stall.toSeconds());
    }

    /** On a fresh process nothing is executing yet, so every RUNNING task is an orphan to resume. */
    private void recoverInterruptedAfterRestart() {
        List<Task> running = runningTasks();
        if (running.isEmpty()) {
            return;
        }
        log.info("restart recovery: {} task(s) were RUNNING, resuming them", running.size());
        for (Task t : running) {
            recoverSafely(t.id());
        }
    }

    private void scanSafely() {
        try {
            Set<String> active = recovery.activeTaskIds();
            Instant cutoff = Instant.now().minus(stall);
            for (Task t : runningTasks()) {
                boolean notHere = !active.contains(t.id());
                boolean notAdvancing = t.updatedAt() == null || t.updatedAt().isBefore(cutoff);
                if (notHere && notAdvancing) {
                    log.warn("watchdog: task {} is RUNNING but not executing here and stalled since {} — recovering",
                            t.id(), t.updatedAt());
                    recoverSafely(t.id());
                }
            }
        } catch (RuntimeException e) {
            log.warn("watchdog scan failed (will retry next tick): {}", e.getMessage());
        }
    }

    private void recoverSafely(String taskId) {
        try {
            recovery.recover(taskId);
        } catch (RuntimeException e) {
            log.warn("watchdog could not recover task {}: {}", taskId, e.getMessage());
        }
    }

    private List<Task> runningTasks() {
        return tasks.listTasks().stream().filter(t -> t.status() == TaskStatus.RUNNING).toList();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
