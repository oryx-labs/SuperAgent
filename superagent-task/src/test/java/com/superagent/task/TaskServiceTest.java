package com.superagent.task;

import com.superagent.api.chat.ImageUploadDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskPageDto;
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
import com.superagent.core.task.TaskStore;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class TaskServiceTest {

    @org.junit.jupiter.api.BeforeEach
    void inCompany() {
        com.superagent.core.tenant.TenantContext.set("acme"); // work handed to other threads carries the company
    }

    @org.junit.jupiter.api.AfterEach
    void leaveCompany() {
        com.superagent.core.tenant.TenantContext.clear();
    }

    private static final ImageAttachmentStore IMAGES =
            new ImageAttachmentStore(System.getProperty("java.io.tmpdir") + "/sa-test-images-" + UUID.randomUUID());
    /** Just the PNG signature: enough for the store's format sniffing. */
    private static final String PNG = Base64.getEncoder().encodeToString(
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});

    private static final class InMemoryTaskStore implements TaskStore {
        private final Map<String, Task> rows = new LinkedHashMap<>();
        @Override public List<Task> listTasks() { return new ArrayList<>(rows.values()); }
        @Override public Optional<Task> findTask(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized Task save(Task t) { rows.put(t.id(), t); return t; }
        @Override public void delete(String id) { rows.remove(id); }
    }

    private static final class InMemoryTaskPhaseStore implements TaskPhaseStore {
        private final Map<String, String> rows = new LinkedHashMap<>();
        @Override public synchronized Optional<String> findPhasesJson(String taskId) { return Optional.ofNullable(rows.get(taskId)); }
        @Override public synchronized void save(String taskId, String json) { rows.put(taskId, json); }
        @Override public synchronized void delete(String taskId) { rows.remove(taskId); }
    }

    /** Minimal ChatStore: only the message/event storage tasks use is backed; sessions are unused. */
    private static final class InMemoryRunStore implements ChatStore {
        private final Map<String, List<ChatMessage>> messages = new LinkedHashMap<>();
        private final Map<String, List<AgentEvent>> events = new LinkedHashMap<>();
        @Override public ChatSession createSession(ChatSession s) { return s; }
        @Override public Optional<ChatSession> findSession(String id) { return Optional.empty(); }
        @Override public List<ChatSession> listSessions() { return List.of(); }
        @Override public List<ChatSession> listSessionsByAgent(String agentId) { return List.of(); }
        @Override public void updateSession(ChatSession s) { }
        @Override public synchronized ChatMessage appendMessage(ChatMessage m) {
            messages.computeIfAbsent(m.sessionId(), k -> new ArrayList<>()).add(m); return m;
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
    }

    /** Fake runner that emits a tool-use + text + result; records the session id of each run. */
    private static final class FakeRunner implements AgentRunner {
        private final Supplier<String> turnText;
        private final List<String> sessionIds;
        FakeRunner(Supplier<String> turnText) { this(turnText, null); }
        FakeRunner(Supplier<String> turnText, List<String> sessionIds) { this.turnText = turnText; this.sessionIds = sessionIds; }
        @Override public AgentType type() { return AgentType.CLAUDE; }
        @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
            if (sessionIds != null) { sessionIds.add(req.sessionId()); }
            String out = turnText.get();
            sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.TOOL_USE, "Edit", Instant.now()));
            sink.emit(new AgentEvent(req.sessionId(), null, 1, AgentEventType.TEXT, out, Instant.now()));
            sink.emit(new AgentEvent(req.sessionId(), null, 2, AgentEventType.RESULT, out, Instant.now()));
        }
    }

    private TaskService service(AgentRunner runner, InMemoryTaskStore tasks) {
        return service(runner, tasks, new InMemoryTaskPhaseStore(), 50);
    }

    private TaskService service(AgentRunner runner, InMemoryTaskStore tasks, int maxParallel) {
        return service(runner, tasks, new InMemoryTaskPhaseStore(), maxParallel);
    }

    private TaskService service(AgentRunner runner, InMemoryTaskStore tasks, InMemoryTaskPhaseStore phases, int maxParallel) {
        return new TaskService(tasks, phases, new InMemoryTaskRunStateStore(), new InMemoryRunStore(), List.of(runner), IMAGES,
                System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID(), "", "", false, maxParallel, "tester", null, null, null, null, null);
    }

    private static final class InMemoryTaskRunStateStore implements TaskRunStateStore {
        private final Map<String, TaskRunState> rows = new LinkedHashMap<>();
        @Override public Optional<TaskRunState> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized TaskRunState save(TaskRunState s) { rows.put(s.taskId(), s); return s; }
        @Override public void delete(String id) { rows.remove(id); }
    }

    /** A non-coding task, so the generic single-pass flow runs regardless of title wording. */
    private static CreateTaskRequest brief(String title) {
        return new CreateTaskRequest(title, "Do the thing and verify it.", null, "my-repo", "main", null, null, null, "general");
    }

    private static CreateTaskRequest coding(String title) {
        return new CreateTaskRequest(title, "实现并验收这个前端改动。", null, "my-repo", "main", null, null, null, "coding");
    }

    @Test
    void createRunsToDoneWithOutputAndEvents() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        TaskService service = service(new FakeRunner(() -> "交付完成：实现并通过测试。"), tasks);

        String id = service.create(brief("Build widget")).id();

        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("DONE"));

        Task done = tasks.findTask(id).orElseThrow();
        assertThat(done.progress()).isEqualTo(100);
        assertThat(done.finishedAt()).isNotNull();

        TaskDetailDto detail = service.detail(id, "tester");
        assertThat(detail.events()).isNotEmpty();
        assertThat(detail.messages()).anyMatch(m -> m.role().equals("assistant"));
    }

    @Test
    void pausesForConfirmationThenResumesToDone() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        AtomicInteger turn = new AtomicInteger();
        // Turn 0 asks for confirmation; turn 1 (the resume) completes.
        AgentRunner runner = new FakeRunner(() ->
                turn.getAndIncrement() == 0 ? "NEEDS_CONFIRMATION: 用哪个数据库？" : "已按 MySQL 完成。");
        TaskService service = service(runner, tasks);

        String id = service.create(brief("Ambiguous task")).id();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("NEEDS_CONFIRMATION"));

        service.sendMessage(id, "tester", "用 MySQL");
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("DONE"));

        assertThat(tasks.findTask(id).orElseThrow().progress()).isEqualTo(100);
    }

    @Test
    void sendMessageToRunningTaskIsRejected() throws Exception {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        // Runner blocks so the task stays RUNNING while we poke it.
        AgentRunner slow = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "done", Instant.now()));
            }
        };
        TaskService service = service(slow, tasks);
        String id = service.create(brief("Slow task")).id();

        await().atMost(Duration.ofSeconds(2)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("RUNNING"));
        assertThatThrownBy(() -> service.sendMessage(id, "tester", "hi")).isInstanceOf(TaskBusyException.class);
    }

    @Test
    void theListIsPagedNewestFirstAndAPagePastTheEndShowsTheLastOne() throws InterruptedException {
        TaskService service = service(new FakeRunner(() -> "done."), new InMemoryTaskStore());
        for (String title : List.of("A", "B", "C")) {
            service.create(brief(title));
            Thread.sleep(5); // distinct creation times, so "newest first" is well defined
        }

        TaskPageDto first = service.page("tester", null, 1, 2);
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(TaskDto::title).containsExactly("C", "B");
        assertThat(service.page("tester", null, 2, 2).items()).extracting(TaskDto::title).containsExactly("A");
        assertThat(service.page("tester", null, 9, 2).page()).isEqualTo(2);
    }

    @Test
    void someoneElsesTaskDoesNotExistForYou() {
        TaskService service = service(new FakeRunner(() -> "done."), new InMemoryTaskStore());
        String id = service.create(brief("Mine"), "alice").id();

        assertThat(service.list("bob")).isEmpty();
        assertThat(service.page("bob", null, 1, 20).total()).isZero();
        assertThat(service.stats("bob").total()).isZero();
        assertThatThrownBy(() -> service.detail(id, "bob")).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.sendMessage(id, "bob", "hi")).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.delete(id, "bob")).isInstanceOf(TaskNotFoundException.class);
        assertThat(service.list("alice")).extracting(TaskDto::title).containsExactly("Mine");
    }

    @Test
    void missingTaskYields404() {
        TaskService service = service(new FakeRunner(() -> "x"), new InMemoryTaskStore());
        assertThatThrownBy(() -> service.detail("nope", "tester")).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void sequentialTasksReuseTheSameSession() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<String> sessionIds = Collections.synchronizedList(new ArrayList<>());
        TaskService service = service(new FakeRunner(() -> "done.", sessionIds), tasks, 5);

        String a = service.create(brief("A")).id();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(a).map(t -> t.status().name()).orElse("").equals("DONE"));
        String b = service.create(brief("B")).id();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(b).map(t -> t.status().name()).orElse("").equals("DONE"));

        assertThat(sessionIds).hasSize(2);
        assertThat(sessionIds.get(0)).isEqualTo(sessionIds.get(1)); // B reused A's now-idle session
    }

    @Test
    void overCapTasksQueueUntilASessionFrees() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                if (calls.getAndIncrement() == 0) {
                    try { gate.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "done", Instant.now()));
            }
        };
        TaskService service = service(runner, tasks, 1); // one parallel slot per owner

        String a = service.create(brief("A")).id();
        await().atMost(Duration.ofSeconds(2)).until(() ->
                tasks.findTask(a).map(t -> t.status().name()).orElse("").equals("RUNNING"));
        String b = service.create(brief("B")).id();
        // cap reached and A still busy -> B waits in the queue (not started)
        assertThat(tasks.findTask(b).orElseThrow().status().name()).isEqualTo("PENDING");

        gate.countDown(); // A finishes, its session frees, B is dispatched
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(a).map(t -> t.status().name()).orElse("").equals("DONE"));
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(b).map(t -> t.status().name()).orElse("").equals("DONE"));
    }

    @Test
    void codingRunsThreeStepsToDoneWithPrdAndEvidence() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        InMemoryTaskPhaseStore phases = new InMemoryTaskPhaseStore();
        AtomicInteger turn = new AtomicInteger();
        // One turn per phase: PRD markdown, dev summary, acceptance evidence.
        AgentRunner runner = new FakeRunner(() -> switch (turn.getAndIncrement()) {
            case 0 -> "## 背景\n...\n## 需求\n...\n## 技术思路\n...";
            case 1 -> "已修改 app.js、index.html。";
            default -> "构建通过；关键 DOM 断言通过。";
        });
        TaskService service = service(runner, tasks, phases, 50);

        String id = service.create(coding("改一个前端按钮")).id();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("DONE"));

        assertThat(tasks.findTask(id).orElseThrow().progress()).isEqualTo(100);

        TaskDetailDto detail = service.detail(id, "tester");
        assertThat(detail.phases()).hasSize(3);
        assertThat(detail.phases()).allMatch(p -> p.status().equals("done"));
        assertThat(detail.phases().get(0).content()).contains("## 背景");   // PRD stored in DB
        assertThat(detail.phases().get(2).evidence()).isNotBlank();         // acceptance evidence
    }

    @Test
    void codingPausesAtGateWhenPrdMissing() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        TaskService service = service(new FakeRunner(() -> ""), tasks, new InMemoryTaskPhaseStore(), 50);

        String id = service.create(coding("写点代码")).id();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("NEEDS_CONFIRMATION"));

        TaskDetailDto detail = service.detail(id, "tester");
        assertThat(detail.messages()).anyMatch(m -> m.content().contains("第1步未产出 PRD"));
        assertThat(detail.phases().get(0).status()).isEqualTo("running"); // stays on the PRD step for resume
    }

    @Test
    void recoverResumesAnOrphanedRunningTask() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        TaskService service = service(new FakeRunner(() -> "交付完成。"), tasks);

        String id = service.create(brief("orphan")).id();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("DONE"));

        // Simulate a crash: the store still says RUNNING but nothing is executing it.
        tasks.save(tasks.findTask(id).orElseThrow().withStatus(com.superagent.core.task.TaskStatus.RUNNING));
        assertThat(service.activeTaskIds()).doesNotContain(id);

        service.recover(id); // the watchdog's action
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals("DONE"));
    }

    /** Records each agent run's session wiring; blocks the run whose 1-based index is {@code blockAt} until released. */
    private static final class RecordingRunner implements AgentRunner {
        record Call(String sessionId, String resumeId, String workspaceDir, int images) { }
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch gate = new CountDownLatch(1);
        final int blockAt;
        RecordingRunner(int blockAt) { this.blockAt = blockAt; }
        @Override public AgentType type() { return AgentType.CLAUDE; }
        @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
            calls.add(new Call(req.sessionId(), req.agentSessionId(), req.workspaceDir(), req.images().size()));
            if (calls.size() == blockAt) {
                try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "done", Instant.now()));
        }
    }

    private static void awaitStatus(InMemoryTaskStore tasks, String id, String status) {
        await().atMost(Duration.ofSeconds(5)).until(() ->
                tasks.findTask(id).map(t -> t.status().name()).orElse("").equals(status));
    }

    @Test
    void steeringImagesGoToTheNextTurnOnlyAndShowOnTheMessage() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        RecordingRunner runner = new RecordingRunner(0);
        TaskService service = new TaskService(tasks, new InMemoryTaskPhaseStore(), new InMemoryTaskRunStateStore(),
                new InMemoryRunStore(), List.of(runner), IMAGES,
                System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID(), "", "", false, 5, "tester", null, null, null, null, null);
        String id = service.create(brief("A")).id();
        awaitStatus(tasks, id, "DONE");

        service.sendMessage(id, "tester", "", List.of(new ImageUploadDto("image/png", PNG)), List.of()); // image-only is allowed
        awaitStatus(tasks, id, "DONE");
        assertThat(runner.calls.get(1).images()).isEqualTo(1);
        assertThat(service.detail(id, "tester").messages()).filteredOn(m -> m.role().equals("user")).singleElement()
                .satisfies(m -> assertThat(m.images()).singleElement().asString()
                        .startsWith("/api/v1/tasks/" + id + "/messages/"));

        service.sendMessage(id, "tester", "and now without one");
        awaitStatus(tasks, id, "DONE");
        assertThat(runner.calls.get(2).images()).isZero();
    }

    @Test
    void steeringResumesTheTasksOwnSessionEvenAfterARestart() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        InMemoryTaskPhaseStore phases = new InMemoryTaskPhaseStore();
        InMemoryTaskRunStateStore runStates = new InMemoryTaskRunStateStore();
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingRunner runner = new RecordingRunner(0);
        String root = System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID();
        TaskService service = new TaskService(tasks, phases, runStates, runStore, List.of(runner), IMAGES,
                root, "", "", false, 5, "tester", null, null, null, null, null);

        String id = service.create(brief("A")).id();
        awaitStatus(tasks, id, "DONE");
        RecordingRunner.Call first = runner.calls.get(0);
        assertThat(first.resumeId()).isNull(); // first turn mints the session

        service.sendMessage(id, "tester", "fix the edge case");
        awaitStatus(tasks, id, "DONE");
        RecordingRunner.Call steer = runner.calls.get(1);
        assertThat(steer.sessionId()).isEqualTo(first.sessionId());
        assertThat(steer.resumeId()).isEqualTo(first.sessionId()); // --resume, not a new session
        assertThat(steer.workspaceDir()).isEqualTo(first.workspaceDir());
        assertThat(service.detail(id, "tester").task().sessionId()).isEqualTo(first.sessionId());

        // A new process starts with an empty pool: steering must still resume the same session.
        TaskService restarted = new TaskService(tasks, phases, runStates, runStore, List.of(runner), IMAGES,
                root, "", "", false, 5, "tester", null, null, null, null, null);
        restarted.restoreSessions(); // what each company's start does
        restarted.sendMessage(id, "tester", "one more fix");
        awaitStatus(tasks, id, "DONE");
        RecordingRunner.Call afterRestart = runner.calls.get(2);
        assertThat(afterRestart.resumeId()).isEqualTo(first.sessionId());
        assertThat(afterRestart.workspaceDir()).isEqualTo(first.workspaceDir());
    }

    @Test
    void steeringWaitsWhileAnotherTaskHoldsTheSession() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        RecordingRunner runner = new RecordingRunner(2); // block task B's run
        TaskService service = service(runner, tasks, 5);

        String a = service.create(brief("A")).id();
        awaitStatus(tasks, a, "DONE");
        String b = service.create(brief("B")).id(); // reuses A's now-idle session
        await().atMost(Duration.ofSeconds(5)).until(() -> runner.calls.size() == 2);
        assertThat(runner.calls.get(1).sessionId()).isEqualTo(runner.calls.get(0).sessionId());

        service.sendMessage(a, "tester", "rework");
        assertThat(tasks.findTask(a).orElseThrow().status().name()).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.sendMessage(a, "tester", "again")).isInstanceOf(TaskBusyException.class);

        runner.gate.countDown(); // B finishes and frees the session; A's steering run takes it
        awaitStatus(tasks, b, "DONE");
        awaitStatus(tasks, a, "DONE");
        assertThat(runner.calls).hasSize(3);
        assertThat(runner.calls.get(2).resumeId()).isEqualTo(runner.calls.get(0).sessionId());
    }

    /** Records which session each run used; one per CLI type. */
    private static final class TypedRunner implements AgentRunner {
        final AgentType type;
        final List<RecordingRunner.Call> calls;
        TypedRunner(AgentType type, List<RecordingRunner.Call> calls) { this.type = type; this.calls = calls; }
        @Override public AgentType type() { return type; }
        @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
            calls.add(new RecordingRunner.Call(req.sessionId(), req.agentSessionId(), req.workspaceDir(), req.images().size()));
            sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "done", Instant.now()));
        }
    }

    private static CreateTaskRequest briefFor(String title, String agent, String model) {
        return new CreateTaskRequest(title, "Do it.", null, null, null, null, model, agent, "general");
    }

    @Test
    void sessionsAreReusedOnlyWithinTheSameCli() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<RecordingRunner.Call> claude = Collections.synchronizedList(new ArrayList<>());
        List<RecordingRunner.Call> codex = Collections.synchronizedList(new ArrayList<>());
        TaskService service = new TaskService(tasks, new InMemoryTaskPhaseStore(), new InMemoryTaskRunStateStore(),
                new InMemoryRunStore(), List.of(new TypedRunner(AgentType.CLAUDE, claude), new TypedRunner(AgentType.CODEX, codex)), IMAGES,
                System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID(), "", "", false, 5, "tester", null, null, null, null, null);

        String a = service.create(briefFor("A", "claude", null)).id();
        awaitStatus(tasks, a, "DONE");
        String b = service.create(briefFor("B", "codex", "gpt-6-sol")).id();
        awaitStatus(tasks, b, "DONE");
        String c = service.create(briefFor("C", "claude", "claude-opus-5-5")).id();
        awaitStatus(tasks, c, "DONE");

        assertThat(codex.get(0).sessionId()).isNotEqualTo(claude.get(0).sessionId()); // codex never takes a claude session
        assertThat(claude.get(1).sessionId()).isEqualTo(claude.get(0).sessionId());   // the next claude task reuses it
        assertThat(service.detail(b, "tester").task().agentType()).isEqualTo("codex");
        assertThat(service.detail(b, "tester").task().model()).isEqualTo("gpt-6-sol");
    }

    @Test
    void afterARestartNewTasksReuseSessionsFinishedTasksLeft() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        InMemoryTaskPhaseStore phases = new InMemoryTaskPhaseStore();
        InMemoryTaskRunStateStore runStates = new InMemoryTaskRunStateStore();
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingRunner runner = new RecordingRunner(0);
        String root = System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID();
        TaskService first = new TaskService(tasks, phases, runStates, runStore, List.of(runner), IMAGES, root, "", "", false, 5, "tester", null, null, null, null, null);
        String a = first.create(brief("A")).id();
        awaitStatus(tasks, a, "DONE");

        TaskService restarted = new TaskService(tasks, phases, runStates, runStore, List.of(runner), IMAGES, root, "", "", false, 5, "tester", null, null, null, null, null);
        restarted.restoreSessions(); // what each company's start does
        String b = restarted.create(brief("B")).id();
        awaitStatus(tasks, b, "DONE");
        assertThat(runner.calls.get(1).sessionId()).isEqualTo(runner.calls.get(0).sessionId());
        assertThat(runner.calls.get(1).resumeId()).isEqualTo(runner.calls.get(0).sessionId()); // resumed, not re-minted
    }

    @Test
    void creatingATaskForACliWithoutARunnerIsRejected() {
        TaskService service = service(new FakeRunner(() -> "x"), new InMemoryTaskStore());
        assertThatThrownBy(() -> service.create(briefFor("K", "kimi", null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list("tester")).isEmpty();
    }

    @Test
    void anAgentFailureFailsTheTaskInsteadOfPausingAtAGate() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        AgentRunner failing = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.ERROR, "boom: cli crashed", Instant.now()));
            }
        };
        TaskService service = service(failing, tasks);
        String id = service.create(coding("写点代码")).id();
        awaitStatus(tasks, id, "ERROR");
        assertThat(tasks.findTask(id).orElseThrow().error()).contains("boom: cli crashed");
    }

    /** The user's case: A's session was later taken by B, which now waits for a confirmation. */
    @Test
    void steeringRunsInASessionReservedByAnotherWaitingTaskAndHandsTheReservationBack() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<RecordingRunner.Call> calls = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger n = new AtomicInteger();
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                calls.add(new RecordingRunner.Call(req.sessionId(), req.agentSessionId(), req.workspaceDir(), req.images().size()));
                String out = n.incrementAndGet() == 2 ? "NEEDS_CONFIRMATION: which db?" : "done";
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, out, Instant.now()));
            }
        };
        TaskService service = service(runner, tasks, 5);

        String a = service.create(brief("A")).id();
        awaitStatus(tasks, a, "DONE");
        String b = service.create(brief("B")).id();                 // reuses A's idle session, then pauses
        awaitStatus(tasks, b, "NEEDS_CONFIRMATION");
        String shared = calls.get(0).sessionId();
        assertThat(calls.get(1).sessionId()).isEqualTo(shared);

        service.sendMessage(a, "tester", "rework");                           // must not wait on B's reply
        awaitStatus(tasks, a, "DONE");
        assertThat(calls.get(2).sessionId()).isEqualTo(shared);

        String c = service.create(brief("C")).id();                 // the session is still reserved for B
        awaitStatus(tasks, c, "DONE");
        assertThat(calls.get(3).sessionId()).isNotEqualTo(shared);

        service.sendMessage(b, "tester", "mysql");                            // B's reply continues in the shared session
        awaitStatus(tasks, b, "DONE");
        assertThat(calls.get(4).sessionId()).isEqualTo(shared);
    }

    @Test
    void aSteeringMessageWaitingWhenTheProcessStoppedRunsAfterARestart() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        InMemoryTaskPhaseStore phases = new InMemoryTaskPhaseStore();
        InMemoryTaskRunStateStore runStates = new InMemoryTaskRunStateStore();
        InMemoryRunStore runStore = new InMemoryRunStore();
        RecordingRunner runner = new RecordingRunner(0);
        String root = System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID();
        TaskService first = new TaskService(tasks, phases, runStates, runStore, List.of(runner), IMAGES, root, "", "", false, 5, "tester", null, null, null, null, null);
        String a = first.create(brief("A")).id();
        awaitStatus(tasks, a, "DONE");
        // Simulate the crash window: message stored and task PENDING, but the in-memory queue is gone.
        runStore.appendMessage(new ChatMessage(UUID.randomUUID().toString(), a,
                com.superagent.core.chat.MessageRole.USER, "please continue", Instant.now()));
        tasks.save(tasks.findTask(a).orElseThrow().withStatus(com.superagent.core.task.TaskStatus.PENDING));

        new TaskService(tasks, phases, runStates, runStore, List.of(runner), IMAGES, root, "", "", false, 5, "tester", null, null, null, null, null)
                .restoreSessions(); // what each company's start does
        awaitStatus(tasks, a, "DONE");
        assertThat(runner.calls).hasSize(2);
        assertThat(runner.calls.get(1).resumeId()).isEqualTo(runner.calls.get(0).sessionId());
    }

    @Test
    void reworkOnAFinishedCodingTaskActuallyRunsTheAgent() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                prompts.add(req.prompt());
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "## 结果\n已完成，测试通过。", Instant.now()));
            }
        };
        TaskService service = service(runner, tasks);
        String id = service.create(coding("改点代码")).id();
        awaitStatus(tasks, id, "DONE");
        int phaseTurns = prompts.size();

        service.sendMessage(id, "tester", "咋没效果，需要重新编译吗");
        await().atMost(Duration.ofSeconds(5)).until(() -> prompts.size() == phaseTurns + 1);
        awaitStatus(tasks, id, "DONE");
        assertThat(prompts.get(phaseTurns)).isEqualTo("咋没效果，需要重新编译吗");
        assertThat(service.detail(id, "tester").messages()).anyMatch(m -> m.role().equals("assistant") && m.content().contains("已完成"));
    }

    /**
     * A new task is only recorded; the scheduler admits it, its model is chosen first, and when it fails on that model
     * it moves up a tier and starts over, every decision kept.
     */
    @Test
    void aTaskWaitsForTheSchedulerRunsOnTheChosenModelAndMovesUpWhenItFails() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<String> models = Collections.synchronizedList(new ArrayList<>());
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                models.add(req.model());
                boolean cheap = "cheap".equals(req.model());
                sink.emit(new AgentEvent(req.sessionId(), null, 0, cheap ? AgentEventType.ERROR : AgentEventType.RESULT,
                        cheap ? "not up to it" : "done well", Instant.now()));
            }
        };
        List<com.superagent.core.task.TaskModelChoice> kept = Collections.synchronizedList(new ArrayList<>());
        com.superagent.core.task.TaskModelChoiceStore store = new com.superagent.core.task.TaskModelChoiceStore() {
            @Override public void save(com.superagent.core.task.TaskModelChoice c) { kept.add(c); }
            @Override public List<com.superagent.core.task.TaskModelChoice> list(String id) {
                return kept.stream().filter(c -> c.taskId().equals(id)).toList();
            }
        };
        com.superagent.core.task.TaskModelSelector selector = new com.superagent.core.task.TaskModelSelector() {
            @Override public boolean enabled() { return true; }
            @Override public Optional<com.superagent.core.task.TaskModelChoice> choose(Task t) {
                return Optional.of(new com.superagent.core.task.TaskModelChoice(t.id(), 0, "judge", "easy", "fast", "cheap",
                        "claude", "judge-model", "- cheap\n- strong", "easy one", Instant.now()));
            }
            @Override public Optional<com.superagent.core.task.TaskModelChoice> escalate(Task t, String failure) {
                return "cheap".equals(t.model()) ? Optional.of(new com.superagent.core.task.TaskModelChoice(t.id(), 0,
                        "escalation", null, "strong", "strong", "claude", null, null, failure, Instant.now())) : Optional.empty();
            }
        };
        TaskService service = service(runner, tasks);
        service.setModelSelection(selector, store);
        service.useScheduler(() -> { });

        String id = service.create(brief("整理会议纪要")).id();
        assertThat(tasks.findTask(id).orElseThrow().status().name()).isEqualTo("PENDING");
        assertThat(models).isEmpty(); // recorded, not run

        service.awaitingAdmission().forEach(service::admit);
        await().atMost(Duration.ofSeconds(5)).until(() -> com.superagent.core.tenant.TenantContext.call("acme",
                () -> service.awaitingAdmission().size() == 1
                        && "strong".equals(tasks.findTask(id).orElseThrow().model()))); // failed on cheap, queued again one tier up
        service.awaitingAdmission().forEach(service::admit);
        awaitStatus(tasks, id, "DONE");

        assertThat(models).containsExactly("cheap", "strong");
        assertThat(service.modelChoices(id, "tester")).extracting(c -> c.by() + ":" + c.model() + "#" + c.seq())
                .containsExactly("judge:cheap#1", "escalation:strong#2");
        assertThat(service.modelChoices(id, "tester").get(1).reasoning()).contains("not up to it");
    }

    @Test
    void withTiersOffATaskSaysWhyItRunsOnItsModel() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<com.superagent.core.task.TaskModelChoice> kept = Collections.synchronizedList(new ArrayList<>());
        com.superagent.core.task.TaskModelChoiceStore store = new com.superagent.core.task.TaskModelChoiceStore() {
            @Override public void save(com.superagent.core.task.TaskModelChoice c) { kept.add(c); }
            @Override public List<com.superagent.core.task.TaskModelChoice> list(String id) {
                return kept.stream().filter(c -> c.taskId().equals(id)).toList();
            }
        };
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "ok", Instant.now()));
            }
        };
        TaskService service = service(runner, tasks);
        service.setModelSelection(null, store);
        service.useScheduler(() -> { });

        String id = service.create(new CreateTaskRequest("整理纪要", "x", null, null, null, null, "claude-opus-5-5", null, null)).id();
        assertThat(service.modelChoices(id, "tester")).singleElement().satisfies(c -> {
            assertThat(c.by()).isEqualTo("default");
            assertThat(c.model()).isEqualTo("claude-opus-5-5");
            assertThat(c.reasoning()).contains("没有开启模型分档", "指定了 claude-opus-5-5");
        });
    }

    @Test
    void aTaskRecordsWhereItsTimeWent() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<com.superagent.core.timing.TurnTiming> kept = Collections.synchronizedList(new ArrayList<>());
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.SYSTEM, "sid", Instant.now()));
                sink.emit(new AgentEvent(req.sessionId(), null, 1, AgentEventType.TOOL_USE, "Bash", Instant.now()));
                sink.emit(new AgentEvent(req.sessionId(), null, 2, AgentEventType.TOOL_RESULT, "ok", Instant.now()));
                sink.emit(new AgentEvent(req.sessionId(), null, 3, AgentEventType.RESULT, "done", Instant.now()));
            }
        };
        TaskService service = service(runner, tasks);
        service.setTimings(new com.superagent.core.timing.TurnTimingStore() {
            @Override public void save(com.superagent.core.timing.TurnTiming t) { kept.add(t); }
            @Override public List<com.superagent.core.timing.TurnTiming> forSessions(java.util.Collection<String> ids) {
                return kept.stream().filter(t -> ids.contains(t.sessionId())).toList();
            }
        });
        String id = service.create(brief("整理会议纪要")).id();
        awaitStatus(tasks, id, "DONE");

        var timing = service.timing(id, "tester");
        assertThat(timing.open()).isFalse();
        assertThat(timing.segments()).filteredOn(s -> s.kind().equals("turn")).singleElement().satisfies(s -> {
            assertThat(s.turn().attempts()).isEqualTo(1);
            assertThat(s.turn().tools()).extracting(com.superagent.api.timing.TurnTimingDto.Tool::name).containsExactly("Bash");
        });
    }

    // --- complex tasks, follow-ups, tags ---

    private static final class InMemoryLinks implements com.superagent.core.task.TaskLinkStore {
        private final List<com.superagent.core.task.TaskLink> rows = Collections.synchronizedList(new ArrayList<>());
        @Override public void save(com.superagent.core.task.TaskLink l) { rows.add(l); }
        @Override public Optional<com.superagent.core.task.TaskLink> find(String id) {
            synchronized (rows) { return rows.stream().filter(l -> l.taskId().equals(id)).findFirst(); }
        }
        @Override public List<com.superagent.core.task.TaskLink> all() { synchronized (rows) { return new ArrayList<>(rows); } }
    }

    private static final class InMemoryTags implements com.superagent.core.task.TaskTagStore {
        private final Map<String, List<String>> rows = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public void set(String id, List<String> tags) { rows.put(id, tags); }
        @Override public List<String> tags(String id) { return rows.getOrDefault(id, List.of()); }
        @Override public Map<String, List<String>> all() { return Map.copyOf(rows); }
    }

    /** What the scheduler thread does: sum up finished complex tasks, admit what may start. */
    private static Thread schedulerLoop(TaskService service) {
        service.useScheduler(() -> { });
        Thread t = new Thread(com.superagent.core.tenant.TenantContext.wrap(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                service.sumUpFinished();
                service.awaitingAdmission().forEach(service::admit);
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        }));
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void reply(AgentRunRequest req, AgentEventSink sink, String text) {
        sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, text, Instant.now()));
    }

    @Test
    void aComplexTaskHandsOutSubtasksRunsThemSideBySideAndSumsThemUp() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        java.util.concurrent.atomic.AtomicReference<TaskService> svc = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> parentId = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                String p = req.prompt();
                if (p.contains("## 这一轮：拆分")) {
                    for (int i = 1; i <= 7; i++) {
                        svc.get().createSubtask(parentId.get(), "tester", "第 " + i + " 块", "调研第 " + i + " 块");
                    }
                    reply(req, sink, "计划：分 7 块同时调研");
                } else if (p.startsWith(ComplexTaskPrompts.SUMMARY_MARK)) {
                    summaries.add(p);
                    reply(req, sink, "最终结论：7 块都齐了");
                } else {
                    most.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    running.decrementAndGet();
                    String which = p.substring(p.indexOf("调研第 ")).lines().findFirst().orElse("");
                    reply(req, sink, "结果：" + which + " 查到了");
                }
            }
        };
        TaskService service = service(runner, tasks);
        service.setLinks(new InMemoryLinks());
        svc.set(service);
        Thread loop = schedulerLoop(service);
        try {
            String id = service.create(new CreateTaskRequest("市场全景调研", "分头调研 7 个细分市场，再汇总", null, null, null,
                    null, null, null, "complex")).id();
            parentId.set(id);
            await().atMost(Duration.ofSeconds(15)).until(() -> tasks.findTask(id).orElseThrow().status().name().equals("DONE"));

            assertThat(most.get()).isBetween(2, 5); // side by side, never more than the default limit of 5
            assertThat(summaries).singleElement().satisfies(sum -> {
                assertThat(sum).contains("7 个子任务都结束了（完成 7 个）");
                for (int i = 1; i <= 7; i++) {
                    assertThat(sum).contains("结果：调研第 " + i + " 块 查到了");
                }
            });
            assertThat(service.lastAnswer(id)).isEqualTo("最终结论：7 块都齐了");
            // The list shows the complex task; its subtasks sit under it.
            assertThat(service.list("tester")).singleElement().satisfies(t -> {
                assertThat(t.id()).isEqualTo(id);
                assertThat(t.subtasks()).isEqualTo(7);
                assertThat(t.subtasksFinished()).isEqualTo(7);
            });
            var rel = service.relations(id, "tester");
            assertThat(rel.subtasks()).hasSize(7).allSatisfy(k -> assertThat(k.parentId()).isEqualTo(id));
            assertThat(service.subtaskRoom(id)).isEqualTo(3); // 10 by default, 7 used
            assertThatThrownBy(() -> service.createSubtask(rel.subtasks().get(0).id(), "tester", "x", "y"))
                    .hasMessageContaining("只有复杂任务"); // one level only
        } finally {
            loop.interrupt();
        }
    }

    @Test
    void aSubtaskThatKeepsFailingIsRetriedThenReplacedAndTheConclusionNamesTheGap() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        java.util.concurrent.atomic.AtomicReference<TaskService> svc = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> parentId = new java.util.concurrent.atomic.AtomicReference<>();
        List<String> summaries = Collections.synchronizedList(new ArrayList<>());
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                String p = req.prompt();
                if (p.contains("## 这一轮：拆分")) {
                    svc.get().createSubtask(parentId.get(), "tester", "好的一块", "调研能查到的");
                    svc.get().createSubtask(parentId.get(), "tester", "坏的一块", "调研查不到的");
                    reply(req, sink, "计划：两块");
                } else if (p.startsWith(ComplexTaskPrompts.SUMMARY_MARK)) {
                    summaries.add(p);
                    if (summaries.size() == 1) {
                        svc.get().createSubtask(parentId.get(), "tester", "补派", "换个办法调研");
                        reply(req, sink, "补派了一个");
                    } else {
                        reply(req, sink, "结论：齐了，第一次失败的那块已由补派补上");
                    }
                } else if (p.contains("调研查不到的") || p.startsWith("服务刚恢复") || p.startsWith("上一个会话")) {
                    sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.ERROR, "查不到", Instant.now()));
                } else {
                    reply(req, sink, "查到了");
                }
            }
        };
        TaskService service = service(runner, tasks);
        service.setLinks(new InMemoryLinks());
        svc.set(service);
        Thread loop = schedulerLoop(service);
        try {
            String id = service.create(new CreateTaskRequest("两块调研", "分两块", null, null, null, null, null, null,
                    "complex")).id();
            parentId.set(id);
            await().atMost(Duration.ofSeconds(10)).until(() -> service.children(id).size() == 2);
            Task bad = service.children(id).get(1);
            // What the watchdog does: retry the failed subtask until its retries run out.
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                Task now = tasks.findTask(bad.id()).orElseThrow();
                if (service.finished(now)) {
                    return true;
                }
                if (now.status().name().equals("ERROR")) { // awaitility polls on its own thread
                    com.superagent.core.tenant.TenantContext.run("acme", () -> service.recover(bad.id()));
                }
                return false;
            });
            await().atMost(Duration.ofSeconds(15)).until(() -> tasks.findTask(id).orElseThrow().status().name().equals("DONE"));

            assertThat(summaries).hasSize(2);
            assertThat(summaries.get(0)).contains("完成 1 个，失败 1 个", "失败原因", "可以用 mcp__superagent__create_subtask 补派");
            assertThat(summaries.get(1)).contains("第 2 轮", "3 个子任务都结束了");
            assertThat(service.lastAnswer(id)).contains("补派补上");
            assertThat(service.subtaskRoom(id)).isEqualTo(8); // the replacement didn't use up the limit
        } finally {
            loop.interrupt();
        }
    }

    @Test
    void aFollowUpStartsFromWhatTheTaskBeforeCameToAndTasksGatherUnderTags() {
        InMemoryTaskStore tasks = new InMemoryTaskStore();
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                prompts.add(req.prompt());
                reply(req, sink, prompts.size() == 1 ? "第一轮结论：市场规模 42 亿" : "第二轮结论");
            }
        };
        TaskService service = service(runner, tasks);
        service.setLinks(new InMemoryLinks());
        service.setTagStore(new InMemoryTags());

        String first = service.create(brief("市场规模调研")).id();
        awaitStatus(tasks, first, "DONE");
        CreateTaskRequest next = new CreateTaskRequest("接着查增速", "在上次基础上查增速", null, null, null, null, null, null,
                "general", null, first);
        assertThatThrownBy(() -> service.create(next, "someone-else")).isInstanceOf(TaskNotFoundException.class);
        String second = service.create(next).id();
        awaitStatus(tasks, second, "DONE");

        assertThat(prompts.get(1)).contains("前情", "第一轮结论：市场规模 42 亿", tasks.findTask(first).orElseThrow().workspaceDir(),
                "在上次基础上查增速");
        assertThat(service.relations(first, "tester").followUps()).extracting(TaskDto::id).containsExactly(second);
        assertThat(service.relations(second, "tester").continuesFrom().id()).isEqualTo(first);

        assertThat(service.setTags(first, "tester", List.of(" 竞品 ", "竞品", "#定价", "")).tags()).containsExactly("竞品", "定价");
        service.setTags(second, "tester", List.of("竞品"));
        assertThat(service.page("tester", null, "定价", null, null, 1, 20).items()).extracting(TaskDto::id).containsExactly(first);
        assertThat(service.facets("tester").tags()).extracting(f -> f.key() + "×" + f.count()).containsExactly("竞品×2", "定价×1");
    }
}
