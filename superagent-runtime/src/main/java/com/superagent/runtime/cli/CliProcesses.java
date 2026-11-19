package com.superagent.runtime.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Every CLI / MCP child process this server started and that is still alive. On shutdown whatever is left is ended,
 * with its own children, so no agent keeps writing into a workspace that the next server instance resumes.
 * A static registry: runners are built in many ways (and in tests without Spring), and there is one per process anyway.
 * The Spring bean only hooks the registry into shutdown, after tasks and chats have had their chance to stop cleanly.
 */
@Component
public class CliProcesses implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CliProcesses.class);
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();
    private static final Duration GRACE = Duration.ofSeconds(3);

    private volatile boolean running;

    /** Tracks a started process until it exits; returns it for chaining. */
    public static Process track(Process process) {
        LIVE.add(process);
        process.onExit().thenRun(() -> LIVE.remove(process));
        return process;
    }

    public static int liveCount() {
        LIVE.removeIf(p -> !p.isAlive());
        return LIVE.size();
    }

    /** Ends {@code process} now, together with every process it started. */
    public static void kill(Process process) {
        List<ProcessHandle> children = process.descendants().toList(); // before the parent goes and they're re-parented
        process.destroyForcibly();
        children.forEach(ProcessHandle::destroyForcibly);
    }

    /** Ends every tracked process and its descendants: politely first, then by force. Returns how many were alive. */
    public static int destroyAll() {
        List<Process> all = List.copyOf(LIVE);
        int alive = 0;
        for (Process p : all) {
            if (p.isAlive()) {
                alive++;
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
            }
        }
        if (alive == 0) {
            return 0;
        }
        long deadline = System.nanoTime() + GRACE.toNanos();
        for (Process p : all) {
            try {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    p.waitFor(left, TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Process p : all) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        LIVE.removeAll(all);
        log.info("ended {} CLI process(es) left running at shutdown", alive);
        return alive;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        destroyAll();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops last: tasks and chats (higher phases) wind down before anything is killed. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
