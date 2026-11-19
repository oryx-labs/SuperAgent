package com.superagent.task;

import com.superagent.runtime.cli.CliProcesses;
import com.superagent.task.harness.coding.MergeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Shuts task execution down without wasting the work in progress: nothing new is let in, the running turns (and any
 * local merge) get up to {@code superagent.task.shutdown.timeout} to finish; what is still running then is marked as
 * interrupted by the restart and its processes are ended, and the next start resumes it at once.
 */
@Component
class TaskShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TaskShutdown.class);
    /** After the runs are told to stop, how long they get to unwind (record their state) before the JVM goes. */
    private static final Duration UNWIND = Duration.ofSeconds(10);

    private final TaskService tasks;
    private final TaskScheduler scheduler;
    private final Duration timeout;
    private volatile boolean running;

    TaskShutdown(TaskService tasks, TaskScheduler scheduler,
                 @Value("${superagent.task.shutdown.timeout:120s}") Duration timeout) {
        this.tasks = tasks;
        this.scheduler = scheduler;
        this.timeout = timeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.stop();
        }
        tasks.beginShutdown();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            boolean idle = tasks.awaitIdle(timeout);
            boolean merged = MergeGuard.awaitIdle(remaining(deadline));
            if (idle && merged) {
                log.info("shutdown: every running task turn finished");
                return;
            }
            if (!merged) {
                log.warn("shutdown: a local merge is still running after {}; stopping anyway", timeout);
            }
            Set<String> cut = tasks.interruptRemaining(CliProcesses::destroyAll);
            if (!cut.isEmpty()) {
                tasks.awaitIdle(UNWIND);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tasks.interruptRemaining(CliProcesses::destroyAll);
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before the web server's graceful shutdown and before the process reaper (the lowest phase). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
