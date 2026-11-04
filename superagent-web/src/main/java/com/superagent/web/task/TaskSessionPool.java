package com.superagent.web.task;

import com.superagent.core.chat.AgentType;
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
 * Per-owner pool of reusable agent sessions for running tasks. A task prefers an idle
 * session of the same agent CLI (so it resumes that session's conversation context and
 * workspace); if none is idle and the owner is below {@code maxParallelPerOwner} busy
 * sessions (across all CLIs), a new session is minted; otherwise {@link #acquire} returns
 * {@code null} and the caller queues the task. Sessions never cross CLIs: a Codex task
 * can't resume a Claude conversation.
 *
 * <p>Not thread-safe on its own — {@link TaskService} serializes all access under a
 * single scheduler lock, which keeps acquire / release / queue decisions race-free.
 *
 * <p>Session states: {@code IDLE} (reusable), {@code BUSY} (an agent is running),
 * {@code RESERVED} (a task paused at NEEDS_CONFIRMATION holds it, so no NEW task may take
 * it, but it no longer counts against the running cap). A reservation only keeps out new
 * tasks: a task that already ran in the session can be steered in it meanwhile, and the
 * reservation is handed back to the waiting task when that run ends ({@code reservedFor}).
 */
class TaskSessionPool {

    private static final Logger log = LoggerFactory.getLogger(TaskSessionPool.class);

    enum State { IDLE, BUSY, RESERVED }

    static final class Session {
        final String id;
        final String owner;
        final AgentType agentType;
        final String workspaceDir;
        boolean initialized;      // false until the first turn runs (then resume, not a new session)
        State state = State.IDLE;
        String taskId;            // the task currently bound to this session, if any
        String reservedFor;       // task paused for confirmation that keeps this session, if any

        Session(String id, String owner, AgentType agentType, String workspaceDir) {
            this.id = id;
            this.owner = owner;
            this.agentType = agentType;
            this.workspaceDir = workspaceDir;
        }
    }

    /** A granted session plus whether this run is the session's first turn (new session vs resume). */
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

    /** Reuse an idle session of this CLI, else mint one under the cap, else {@code null} (caller should queue). */
    Lease acquire(String owner, AgentType agentType, String taskId) {
        List<Session> sessions = sessionsOf(owner);
        for (Session s : sessions) {
            if (s.state == State.IDLE && s.agentType == agentType) {
                bind(s, taskId, State.BUSY);
                log.info("task {} reusing session {} ({}, owner {})", taskId, s.id, agentType, owner);
                return new Lease(s, !s.initialized);
            }
        }
        long busy = sessions.stream().filter(s -> s.state == State.BUSY).count();
        if (busy >= maxParallelPerOwner) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        Session s = new Session(id, owner, agentType, createWorkspace(id));
        sessions.add(s);
        bind(s, taskId, State.BUSY);
        log.info("task {} on NEW session {} ({}, owner {}, busy now {}/{})",
                taskId, s.id, agentType, owner, busy + 1, maxParallelPerOwner);
        return new Lease(s, true);
    }

    Session sessionForTask(String taskId) {
        return byTask.get(taskId);
    }

    /**
     * Binds a task to one specific session — used when steering a task, which must
     * continue in the conversation and workspace it already ran in. Returns {@code null}
     * when that session is currently held by another task (caller queues). A session
     * this process doesn't hold is adopted from disk.
     */
    Lease acquireSession(String owner, AgentType agentType, String taskId, String sessionId, String workspaceDir) {
        Session s = findSession(sessionId);
        if (s == null) {
            s = adopt(owner, agentType, taskId, sessionId, workspaceDir, true);
            log.info("task {} resuming session {} (adopted, owner {})", taskId, sessionId, owner);
            return new Lease(s, false);
        }
        if (s.state == State.BUSY && !taskId.equals(s.taskId)) {
            return null; // another task's agent is running in it right now; that ends, so wait
        }
        // IDLE, or RESERVED (no agent running — just a task waiting for a reply): run here now.
        bind(s, taskId, State.BUSY);
        log.info("task {} resuming its own session {} (owner {}{})", taskId, sessionId, owner,
                s.reservedFor != null && !s.reservedFor.equals(taskId) ? ", reservation kept for " + s.reservedFor : "");
        return new Lease(s, !s.initialized);
    }

    Session findSession(String sessionId) {
        for (List<Session> sessions : byOwner.values()) {
            for (Session s : sessions) {
                if (s.id.equals(sessionId)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * Task finished for good. The session goes idle — unless another task is still waiting
     * on a confirmation in it, in which case the reservation goes back to that task.
     */
    void release(Session s) {
        s.initialized = true;
        if (s.taskId != null) {
            byTask.remove(s.taskId);
        }
        if (s.reservedFor != null && !s.reservedFor.equals(s.taskId)) {
            bind(s, s.reservedFor, State.RESERVED);
            return;
        }
        s.reservedFor = null;
        s.state = State.IDLE;
        s.taskId = null;
    }

    /** Task paused for confirmation: it keeps the session (no new task may take it) but frees a running slot. */
    void pause(Session s) {
        s.initialized = true;
        s.state = State.RESERVED;
        s.reservedFor = s.taskId;
    }

    /** A task is gone (deleted): drop any reservation it holds so its session becomes reusable. */
    void dropReservation(String taskId) {
        for (List<Session> sessions : byOwner.values()) {
            for (Session s : sessions) {
                if (taskId.equals(s.reservedFor)) {
                    s.reservedFor = null;
                    if (s.state == State.RESERVED) {
                        byTask.remove(taskId);
                        s.state = State.IDLE;
                        s.taskId = null;
                    }
                }
            }
        }
    }

    /** Task resumed after confirmation: its reserved session runs again. */
    void resumeBusy(Session s) {
        s.state = State.BUSY;
    }

    /**
     * Binds a task BUSY to a session that exists on disk — the one already in the pool if
     * it's known, else a newly registered entry. Used by steering and recovery after a
     * restart, and when rotating onto a fresh session that keeps the old workspace.
     * {@code initialized=true} means the next run resumes it.
     */
    Session adopt(String owner, AgentType agentType, String taskId, String sessionId, String workspaceDir,
                  boolean initialized) {
        Session s = findSession(sessionId);
        if (s == null) {
            s = new Session(sessionId, owner, agentType, workspaceDir);
            s.initialized = initialized;
            sessionsOf(owner).add(s);
        }
        bind(s, taskId, State.BUSY);
        return s;
    }

    /**
     * Startup: re-registers a session that a finished task left on disk, so new tasks can
     * reuse it instead of the pool starting empty after every restart. With a
     * {@code reservedFor} task id the session stays held for that task (it is waiting on a
     * confirmation reply); otherwise it is idle. Missing workspaces are skipped.
     */
    void restore(String owner, AgentType agentType, String sessionId, String workspaceDir, String reservedFor) {
        if (workspaceDir == null || !Files.isDirectory(Path.of(workspaceDir))) {
            return;
        }
        Session s = findSession(sessionId);
        if (s == null) {
            s = new Session(sessionId, owner, agentType, workspaceDir);
            s.initialized = true;
            sessionsOf(owner).add(s);
        }
        if (reservedFor != null) {
            bind(s, reservedFor, State.RESERVED);
            s.reservedFor = reservedFor;
        }
    }

    private List<Session> sessionsOf(String owner) {
        return byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
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
