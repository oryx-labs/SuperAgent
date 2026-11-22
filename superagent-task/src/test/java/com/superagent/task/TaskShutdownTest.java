package com.superagent.task;

import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskPhaseStore;
import com.superagent.core.task.TaskRunState;
import com.superagent.core.task.TaskRunStateStore;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import com.superagent.runtime.cli.CliProcesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** A server shutdown lets running turns finish, cuts short the rest without wasting retries, and the next start resumes them. */
// Ends every tracked CLI process in this JVM: nothing else may run alongside it.
@Isolated
class TaskShutdownTest {

    private static final ImageAttachmentStore IMAGES =
            new ImageAttachmentStore(System.getProperty("java.io.tmpdir") + "/sa-test-images-" + UUID.randomUUID());

    private final Stores stores = new Stores();
    private final String root = System.getProperty("java.io.tmpdir") + "/sa-shutdown-" + UUID.randomUUID();

    @BeforeEach
    void inCompany() {
        TenantContext.set("acme");
    }

    @AfterEach
    void leaveCompany() {
        TenantContext.clear();
    }

    @Test
    void shutdownWaitsForTheRunningTurnToFinish() {
        TaskService service = service(new ScriptedRunner(sink -> sleep(600)));
        String id = startAndAwaitRunning(service);

        long start = System.nanoTime();
        new TaskShutdown(service, null, Duration.ofSeconds(10)).stop();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(stores.tasks.findTask(id).orElseThrow().status()).isEqualTo(TaskStatus.DONE);
        assertThat(stores.runStates.find(id).map(TaskRunState::interruptedByRestart)).isNotEqualTo(Optional.of(true));
    }

    @Test
    void aTurnStillRunningAtTheTimeoutIsCutShortAndResumedAtOnceAfterTheRestart() throws Exception {
        List<Process> started = Collections.synchronizedList(new ArrayList<>());
        List<String> sessions = Collections.synchronizedList(new ArrayList<>());
        TaskService service = service(new ScriptedRunner((req, c) -> {
            sessions.add(req.sessionId());
            Process p = CliProcesses.track(new ProcessBuilder("sh", "-c", "sleep 60 & wait").start());
            started.add(p);
            c.onCancel(p::destroyForcibly);
            p.waitFor();
        }));
        String id = startAndAwaitRunning(service);
        await().atMost(Duration.ofSeconds(5)).until(() -> !started.isEmpty() && started.get(0).descendants().findAny().isPresent());
        List<ProcessHandle> children = started.get(0).descendants().toList();
        int attempts = attempts(id);

        new TaskShutdown(service, null, Duration.ofSeconds(1)).stop();

        assertThat(started.get(0).isAlive()).isFalse();
        for (ProcessHandle child : children) {
            child.onExit().get(5, TimeUnit.SECONDS);
        }
        Task cut = stores.tasks.findTask(id).orElseThrow();
        assertThat(cut.status()).isEqualTo(TaskStatus.RUNNING); // not STOPPED, not ERROR: the restart picks it up
        TaskRunState interrupted = stores.runStates.find(id).orElseThrow();
        assertThat(interrupted.interruptedByRestart()).isTrue();
        assertThat(stores.runStore.listEvents(id)).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(AgentEventType.SYSTEM);
            assertThat(e.payload()).contains("服务重启");
        });

        // The next process: what the watchdog does for every RUNNING task on start.
        List<AgentRunRequest> resumed = Collections.synchronizedList(new ArrayList<>());
        TaskService restarted = service(new ScriptedRunner((req, c) -> resumed.add(req)));
        restarted.restoreSessions();
        restarted.recover(id);

        await().atMost(Duration.ofSeconds(5)).until(() -> stores.tasks.findTask(id).orElseThrow().status() == TaskStatus.DONE);
        assertThat(resumed).singleElement().satisfies(r -> assertThat(r.agentSessionId()).isEqualTo(sessions.get(0))); // --resume
        TaskRunState after = stores.runStates.find(id).orElseThrow();
        assertThat(after.attemptCount()).isEqualTo(attempts);
        assertThat(after.failureCount()).isZero();
        assertThat(after.interruptedByRestart()).isFalse();
    }

    @Test
    void afterShutdownTheSchedulerLetsNoNewTaskIn() {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        TaskService service = service(new ScriptedRunner((req, c) -> ran.add(req.sessionId())));
        com.superagent.core.tenant.Company acme = new com.superagent.core.tenant.Company("acme", "Acme", true, null, null, null,
                null, Instant.now(), Instant.now());
        com.superagent.platform.tenant.CompanyService companies = new com.superagent.platform.tenant.CompanyService(
                new com.superagent.core.tenant.CompanyStore() {
                    @Override public List<com.superagent.core.tenant.Company> list() { return List.of(acme); }
                    @Override public Optional<com.superagent.core.tenant.Company> find(String id) { return Optional.of(acme); }
                    @Override public com.superagent.core.tenant.Company save(com.superagent.core.tenant.Company c) { return c; }
                }, e -> { });
        TaskScheduler scheduler = new TaskScheduler(companies, service, 1);
        scheduler.start();
        String before = service.create(brief()).id();
        await().atMost(Duration.ofSeconds(5)).until(() -> stores.tasks.findTask(before).orElseThrow().status() == TaskStatus.DONE);

        new TaskShutdown(service, scheduler, Duration.ofSeconds(5)).stop();
        String after = service.create(brief()).id();

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> stores.tasks.findTask(after).orElseThrow().status() == TaskStatus.PENDING);
        assertThat(ran).hasSize(1);
    }

    @Test
    void aFirstTurnThatNeverStartedBeforeTheShutdownStartsItsConversationAfterTheRestart() {
        TaskService service = service(new ScriptedRunner((req, c) -> { }));
        service.beginShutdown(); // a queued task let in as a slot freed up during the shutdown
        String id = service.create(brief()).id();
        await().atMost(Duration.ofSeconds(5)).until(() -> stores.runStates.find(id).map(TaskRunState::interruptedByRestart).orElse(false));

        List<AgentRunRequest> resumed = Collections.synchronizedList(new ArrayList<>());
        TaskService restarted = service(new ScriptedRunner((req, c) -> resumed.add(req)));
        restarted.restoreSessions();
        restarted.recover(id);

        await().atMost(Duration.ofSeconds(5)).until(() -> stores.tasks.findTask(id).orElseThrow().status() == TaskStatus.DONE);
        assertThat(resumed).singleElement().satisfies(r -> assertThat(r.isFirstTurn()).isTrue()); // no --resume of a conversation that never was
        assertThat(stores.runStates.find(id).orElseThrow().attemptCount()).isEqualTo(1);
    }

    @Test
    void aRunningTaskNotMarkedAsInterruptedStillCountsAsARetry() {
        TaskService service = service(new ScriptedRunner(sink -> { }));
        String id = service.create(brief()).id();
        await().atMost(Duration.ofSeconds(5)).until(() -> stores.tasks.findTask(id).orElseThrow().status() == TaskStatus.DONE);
        stores.tasks.save(stores.tasks.findTask(id).orElseThrow().withStatus(TaskStatus.RUNNING)); // a crash, not a shutdown
        int attempts = attempts(id);

        service.recover(id);

        await().atMost(Duration.ofSeconds(5)).until(() -> stores.tasks.findTask(id).orElseThrow().status() == TaskStatus.DONE);
        assertThat(stores.runStates.find(id).orElseThrow().attemptCount()).isEqualTo(attempts + 1);
    }

    /** No stored row yet is the first attempt. */
    private int attempts(String id) {
        return stores.runStates.find(id).map(TaskRunState::attemptCount).orElse(1);
    }

    private String startAndAwaitRunning(TaskService service) {
        String id = service.create(brief()).id();
        await().atMost(Duration.ofSeconds(5)).until(() -> service.activeTaskIds().contains(id));
        return id;
    }

    private TaskService service(AgentRunner runner) {
        return new TaskService(stores.tasks, stores.phases, stores.runStates, stores.runStore, List.of(runner), IMAGES,
                root, "", "", false, 5, "tester", null, null, null, null, null);
    }

    private static CreateTaskRequest brief() {
        return new CreateTaskRequest("A", "Do the thing.", null, null, null, null, null, null, "general");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    interface Turn {
        void run(AgentRunRequest req, Cancellation c) throws Exception;
    }

    /** Runs {@code turn}, then ends the turn with a result unless it was cancelled. */
    private static final class ScriptedRunner implements AgentRunner {
        private final Turn turn;

        ScriptedRunner(Turn turn) {
            this.turn = turn;
        }

        ScriptedRunner(java.util.function.Consumer<AgentEventSink> body) {
            this((req, c) -> body.accept(null));
        }

        @Override
        public AgentType type() {
            return AgentType.CLAUDE;
        }

        @Override
        public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
            try {
                turn.run(req, c);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (!c.isCancelled()) {
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "done", Instant.now()));
            }
        }
    }

    private static final class Stores {
        final TaskStore tasks = new TaskStore() {
            private final Map<String, Task> rows = new LinkedHashMap<>();
            @Override public synchronized List<Task> listTasks() { return new ArrayList<>(rows.values()); }
            @Override public synchronized Optional<Task> findTask(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public synchronized Task save(Task t) { rows.put(t.id(), t); return t; }
            @Override public synchronized void delete(String id) { rows.remove(id); }
        };
        final TaskPhaseStore phases = new TaskPhaseStore() {
            private final Map<String, String> rows = new LinkedHashMap<>();
            @Override public synchronized Optional<String> findPhasesJson(String taskId) { return Optional.ofNullable(rows.get(taskId)); }
            @Override public synchronized void save(String taskId, String json) { rows.put(taskId, json); }
            @Override public synchronized void delete(String taskId) { rows.remove(taskId); }
        };
        final TaskRunStateStore runStates = new TaskRunStateStore() {
            private final Map<String, TaskRunState> rows = new LinkedHashMap<>();
            @Override public synchronized Optional<TaskRunState> find(String id) { return Optional.ofNullable(rows.get(id)); }
            @Override public synchronized TaskRunState save(TaskRunState s) { rows.put(s.taskId(), s); return s; }
            @Override public synchronized void delete(String id) { rows.remove(id); }
        };
        final ChatStore runStore = new ChatStore() {
            private final Map<String, List<ChatMessage>> messages = new LinkedHashMap<>();
            private final Map<String, List<AgentEvent>> events = new LinkedHashMap<>();
            @Override public ChatSession createSession(ChatSession s) { return s; }
            @Override public Optional<ChatSession> findSession(String id) { return Optional.empty(); }
            @Override public List<ChatSession> listSessions() { return List.of(); }
            @Override public List<ChatSession> listSessionsByAgent(String agentId) { return List.of(); }
            @Override public void updateSession(ChatSession s) { }
            @Override public synchronized ChatMessage appendMessage(ChatMessage m) {
                messages.computeIfAbsent(m.sessionId(), k -> new ArrayList<>()).add(m);
                return m;
            }
            @Override public synchronized List<ChatMessage> listMessages(String id) {
                return new ArrayList<>(messages.getOrDefault(id, List.of()));
            }
            @Override public void updateMessage(String id, String mid, String content) { }
            @Override public synchronized void appendEvent(AgentEvent e) {
                events.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e);
            }
            @Override public synchronized List<AgentEvent> listEvents(String id) {
                return new ArrayList<>(events.getOrDefault(id, List.of()));
            }
        };
    }
}
