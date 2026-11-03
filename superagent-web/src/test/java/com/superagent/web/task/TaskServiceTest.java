package com.superagent.web.task;

import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDetailDto;
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
import com.superagent.core.task.TaskStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
        return new TaskService(tasks, phases, new InMemoryRunStore(), List.of(runner),
                System.getProperty("java.io.tmpdir") + "/sa-test-" + UUID.randomUUID(), "", "", false, maxParallel, "tester");
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

        TaskDetailDto detail = service.detail(id);
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

        service.sendMessage(id, "用 MySQL");
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
        assertThatThrownBy(() -> service.sendMessage(id, "hi")).isInstanceOf(TaskBusyException.class);
    }

    @Test
    void missingTaskYields404() {
        TaskService service = service(new FakeRunner(() -> "x"), new InMemoryTaskStore());
        assertThatThrownBy(() -> service.detail("nope")).isInstanceOf(TaskNotFoundException.class);
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

        TaskDetailDto detail = service.detail(id);
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

        TaskDetailDto detail = service.detail(id);
        assertThat(detail.messages()).anyMatch(m -> m.content().contains("第1步未产出 PRD"));
        assertThat(detail.phases().get(0).status()).isEqualTo("running"); // stays on the PRD step for resume
    }
}
