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
import com.superagent.core.task.TaskStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Minimal ChatStore: only the message/event storage tasks use is backed; sessions are unused. */
    private static final class InMemoryRunStore implements ChatStore {
        private final Map<String, List<ChatMessage>> messages = new LinkedHashMap<>();
        private final Map<String, List<AgentEvent>> events = new LinkedHashMap<>();
        @Override public ChatSession createSession(ChatSession s) { return s; }
        @Override public Optional<ChatSession> findSession(String id) { return Optional.empty(); }
        @Override public List<ChatSession> listSessions() { return List.of(); }
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

    /** Fake runner that emits a tool-use + text + result; the text per turn comes from {@code turnText}. */
    private static final class FakeRunner implements AgentRunner {
        private final Supplier<String> turnText;
        FakeRunner(Supplier<String> turnText) { this.turnText = turnText; }
        @Override public AgentType type() { return AgentType.CLAUDE; }
        @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
            String out = turnText.get();
            sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.TOOL_USE, "Edit", Instant.now()));
            sink.emit(new AgentEvent(req.sessionId(), null, 1, AgentEventType.TEXT, out, Instant.now()));
            sink.emit(new AgentEvent(req.sessionId(), null, 2, AgentEventType.RESULT, out, Instant.now()));
        }
    }

    private TaskService service(AgentRunner runner, InMemoryTaskStore tasks) {
        return new TaskService(tasks, new InMemoryRunStore(), List.of(runner),
                System.getProperty("java.io.tmpdir") + "/superagent-test-tasks", "", "", false, "tester");
    }

    private static CreateTaskRequest brief(String title) {
        return new CreateTaskRequest(title, "Do the thing and verify it.", null, "my-repo", "main", null, null, null);
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
}
