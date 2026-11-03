package com.superagent.web.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-owner pool of reusable agent sessions for running tasks. A task prefers an
 * idle session (so it resumes that session's conversation context and workspace);
 * if none is idle and the owner is below {@code maxParallelPerOwner} busy sessions,
 * a new session is minted; otherwise {@link #acquire} returns {@code null} and the
 * caller queues the task.
 *
 * <p>Not thread-safe on its own — {@link TaskService} serializes all access under a
 * single scheduler lock, which keeps acquire / release / queue decisions race-free.
 *
 * <p>Session states: {@code IDLE} (reusable), {@code BUSY} (an agent is running),
 * {@code RESERVED} (a task paused at NEEDS_CONFIRMATION still owns it, so it is not
 * reusable, but it no longer counts against the running cap).
 */
class TaskSessionPool {

    private static final Logger log = LoggerFactory.getLogger(TaskSessionPool.class);

    enum State { IDLE, BUSY, RESERVED }

    static final class Session {
        final String id;
        final String owner;
        final String workspaceDir;
        boolean initialized;      // false until the first turn runs (then --resume, not --session-id)
        State state = State.IDLE;
        String taskId;            // the task currently bound to this session, if any

        Session(String id, String owner, String workspaceDir) {
            this.id = id;
            this.owner = owner;
            this.workspaceDir = workspaceDir;
        }
    }

    /** A granted session plus whether this run is the session's first turn (new {@code --session-id} vs {@code --resume}). */
    record Lease(Session session, boolean firstTurn) {
    }

    private final int maxParallelPerOwner;
    private final Path workspaceRoot;
    private final Map<String, List<Session>> byOwner = new HashMap<>();
    private final Map<String, Session> byTask = new HashMap<>();

    TaskSessionPool(int maxParallelPerOwner, Path workspaceRoot) {
        this.maxParallelPerOwner = Math.max(1, maxParallelPerOwner);
        this.workspaceRoot = workspaceRoot;
    }

    /** Reuse an idle session, else mint one under the cap, else {@code null} (caller should queue). */
    Lease acquire(String owner, String taskId) {
        List<Session> sessions = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
        for (Session s : sessions) {
            if (s.state == State.IDLE) {
                bind(s, taskId, State.BUSY);
                log.info("task {} reusing session {} (owner {})", taskId, s.id, owner);
                return new Lease(s, !s.initialized);
            }
        }
        long busy = sessions.stream().filter(s -> s.state == State.BUSY).count();
        if (busy >= maxParallelPerOwner) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        Session s = new Session(id, owner, createWorkspace(id));
        sessions.add(s);
        bind(s, taskId, State.BUSY);
        log.info("task {} on NEW session {} (owner {}, busy now {}/{})", taskId, s.id, owner, busy + 1, maxParallelPerOwner);
        return new Lease(s, true);
    }

    Session sessionForTask(String taskId) {
        return byTask.get(taskId);
    }

    /** Task finished for good: the session goes idle and is free to be reused. */
    void release(Session s) {
        s.initialized = true;
        s.state = State.IDLE;
        if (s.taskId != null) {
            byTask.remove(s.taskId);
        }
        s.taskId = null;
    }

    /** Task paused for confirmation: it keeps the session (not reusable) but frees a running slot. */
    void pause(Session s) {
        s.initialized = true;
        s.state = State.RESERVED;
    }

    /** Task resumed after confirmation: its reserved session runs again. */
    void resumeBusy(Session s) {
        s.state = State.BUSY;
    }

    private void bind(Session s, String taskId, State state) {
        if (s.taskId != null) {
            byTask.remove(s.taskId);
        }
        s.taskId = taskId;
        s.state = state;
        byTask.put(taskId, s);
    }

    private String createWorkspace(String sessionId) {
        Path dir = workspaceRoot.resolve(sessionId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create session workspace", e);
        }
        return dir.toString();
    }
}
