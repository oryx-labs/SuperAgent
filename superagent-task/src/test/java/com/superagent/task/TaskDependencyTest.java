package com.superagent.task;

import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskRefDto;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskDependency;
import com.superagent.core.task.TaskDependencyStore;
import com.superagent.core.task.TaskLink;
import com.superagent.core.task.TaskLinkStore;
import com.superagent.core.task.TaskPhaseStore;
import com.superagent.core.task.TaskRunState;
import com.superagent.core.task.TaskRunStateStore;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Start after": the scheduler holds a task back until what it waits for is done. */
class TaskDependencyTest {

    private final Tasks tasks = new Tasks();
    private final RunStates runStates = new RunStates();
    private final List<NoticeEvents.TaskNote> notes = Collections.synchronizedList(new ArrayList<>());
    private TaskService service;
    private final Dependencies dependencies = new Dependencies();

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        AgentRunner runner = new AgentRunner() {
            @Override public AgentType type() { return AgentType.CLAUDE; }
            @Override public void run(AgentRunRequest req, AgentEventSink sink, Cancellation c) {
                sink.emit(new AgentEvent(req.sessionId(), null, 0, AgentEventType.RESULT, "done", Instant.now()));
            }
        };
        service = new TaskService(tasks, new Phases(), runStates, new Runs(), List.of(runner),
                new ImageAttachmentStore(System.getProperty("java.io.tmpdir") + "/sa-dep-images-" + UUID.randomUUID()),
                System.getProperty("java.io.tmpdir") + "/sa-dep-" + UUID.randomUUID(), "", "", false, 50, "tester",
                null, null, null, e -> {
                    if (e instanceof NoticeEvents.TaskNote n) {
                        notes.add(n);
                    }
                }, null);
        service.setLinks(new Links());
        service.setDependencies(dependencies);
        service.useScheduler(() -> { }); // the test plays the scheduler: nothing starts until it admits it
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CreateTaskRequest request(String title, String continuesFrom, List<String> after) {
        return new CreateTaskRequest(title, "Do it.", null, null, null, null, null, null, "general", null, continuesFrom,
                null, after);
    }

    private String create(String title, List<String> after) {
        return service.create(request(title, null, after), "tester").id();
    }

    private List<String> admissible() {
        return service.awaitingAdmission().stream().map(Task::id).toList();
    }

    private void set(String id, TaskStatus status, String error) {
        Task t = tasks.findTask(id).orElseThrow();
        tasks.save(status == TaskStatus.ERROR || status == TaskStatus.STOPPED || status == TaskStatus.DONE
                ? t.finished(status, t.progress(), error) : t.withStatus(status));
    }

    @Test
    void aTaskWaitsUntilWhatItWaitsForIsDone() {
        String a = create("A", null);
        String b = create("B", List.of(a));
        set(a, TaskStatus.RUNNING, null);

        assertThat(admissible()).doesNotContain(b);
        assertThatThrownBy(() -> service.runNow(b, "tester")).isInstanceOf(TaskNotBlockedException.class); // only waiting
        TaskDto shown = service.list("tester").stream().filter(t -> t.id().equals(b)).findFirst().orElseThrow();
        assertThat(shown.waitingFor()).extracting(TaskRefDto::title).containsExactly("A");
        assertThat(shown.blocked()).isNull();
        assertThat(TaskWaits.line(shown)).contains("等待：「A」(" + a + ") 完成"); // what list_tasks shows the agent

        set(a, TaskStatus.DONE, null);
        assertThat(admissible()).containsExactly(b);
        assertThat(service.status(b, "tester").waitingFor()).isEmpty();

        // Once it has started its rows go; a row left by a creation that failed long ago goes too, a fresh one stays.
        set(b, TaskStatus.RUNNING, null);
        dependencies.save(new TaskDependency("never-saved", a, Instant.now().minus(java.time.Duration.ofHours(1)), null));
        dependencies.save(new TaskDependency("being-created", a, Instant.now(), null));
        admissible();
        assertThat(dependencies.all()).extracting(TaskDependency::taskId).containsExactly("being-created");
    }

    @Test
    void aFollowUpWaitsForTheTaskItCarriesOn() {
        String a = create("A", null);
        String b = service.create(request("B", a, null), "tester").id();

        assertThat(admissible()).containsExactly(a);
        set(a, TaskStatus.DONE, null);
        assertThat(admissible()).containsExactly(b);
    }

    @Test
    void aFailedDependencyHoldsTheTaskBackTellsTheOwnerOnceAndRunNowStartsIt() {
        String a = create("A", null);
        String b = create("B", List.of(a));
        set(a, TaskStatus.ERROR, "编译失败");
        runStates.save(new TaskRunState(a, "s", 10, 10, Instant.now())); // retries used up: it failed for good

        assertThat(admissible()).doesNotContain(b);
        assertThat(admissible()).doesNotContain(b);
        assertThat(notes).singleElement().satisfies(n -> {
            assertThat(n.taskId()).isEqualTo(b);
            assertThat(n.text()).contains("「A」", "编译失败", "直接跑");
        });
        assertThat(service.status(b, "tester").blocked()).contains("「A」", "失败");
        assertThat(TaskWaits.detail(service.status(b, "tester"))).contains(a, "被阻塞", "run_task_now");

        assertThatThrownBy(() -> service.runNow(b, "someone-else")).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.runNow(a, "tester")).isInstanceOf(TaskNotBlockedException.class);
        service.runNow(b, "tester");
        assertThat(admissible()).containsExactly(b);
    }

    @Test
    void aStoppedDependencyHoldsTheTaskBackAndADeletedOneReleasesIt() {
        String a = create("A", null);
        String b = create("B", List.of(a));
        set(a, TaskStatus.STOPPED, null);
        assertThat(admissible()).doesNotContain(b);
        assertThat(notes).singleElement().extracting(NoticeEvents.TaskNote::text).asString().contains("已被停止");

        service.delete(a, "tester");
        assertThat(admissible()).containsExactly(b);
    }

    @Test
    void onlyTasksTheCreatorCanSeeMayBeWaitedFor() {
        String theirs = service.create(request("Theirs", null, null), "bob").id();

        assertThatThrownBy(() -> create("B", List.of(theirs))).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> create("C", List.of("no-such-task"))).isInstanceOf(TaskNotFoundException.class);
        assertThat(tasks.listTasks()).extracting(Task::title).containsExactly("Theirs");
    }

    @Test
    void tasksThatWaitForNothingStillStartTogetherOldestFirst() throws InterruptedException {
        String a = create("A", null);
        Thread.sleep(5);
        String b = create("B", List.of());
        assertThat(admissible()).containsExactly(a, b);
    }

    // --- in-memory stores ---

    private static final class Tasks implements TaskStore {
        private final Map<String, Task> rows = new LinkedHashMap<>();
        @Override public synchronized List<Task> listTasks() { return new ArrayList<>(rows.values()); }
        @Override public synchronized Optional<Task> findTask(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized Task save(Task t) { rows.put(t.id(), t); return t; }
        @Override public synchronized void delete(String id) { rows.remove(id); }
    }

    private static final class Phases implements TaskPhaseStore {
        private final Map<String, String> rows = new LinkedHashMap<>();
        @Override public synchronized Optional<String> findPhasesJson(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized void save(String id, String json) { rows.put(id, json); }
        @Override public synchronized void delete(String id) { rows.remove(id); }
    }

    private static final class RunStates implements TaskRunStateStore {
        private final Map<String, TaskRunState> rows = new LinkedHashMap<>();
        @Override public synchronized Optional<TaskRunState> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public synchronized TaskRunState save(TaskRunState s) { rows.put(s.taskId(), s); return s; }
        @Override public synchronized void delete(String id) { rows.remove(id); }
    }

    private static final class Runs implements ChatStore {
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
        @Override public synchronized List<ChatMessage> listMessages(String id) { return new ArrayList<>(messages.getOrDefault(id, List.of())); }
        @Override public void updateMessage(String id, String mid, String content) { }
        @Override public synchronized void appendEvent(AgentEvent e) { events.computeIfAbsent(e.sessionId(), k -> new ArrayList<>()).add(e); }
        @Override public synchronized List<AgentEvent> listEvents(String id) { return new ArrayList<>(events.getOrDefault(id, List.of())); }
    }

    private static final class Links implements TaskLinkStore {
        private final List<TaskLink> rows = Collections.synchronizedList(new ArrayList<>());
        @Override public void save(TaskLink l) { rows.add(l); }
        @Override public Optional<TaskLink> find(String id) {
            synchronized (rows) { return rows.stream().filter(l -> l.taskId().equals(id)).findFirst(); }
        }
        @Override public List<TaskLink> all() { synchronized (rows) { return new ArrayList<>(rows); } }
    }

    private static final class Dependencies implements TaskDependencyStore {
        private final List<TaskDependency> rows = new ArrayList<>();
        @Override public synchronized void save(TaskDependency d) { rows.add(d); }
        @Override public synchronized List<TaskDependency> of(String id) {
            return rows.stream().filter(d -> d.taskId().equals(id)).toList();
        }
        @Override public synchronized List<TaskDependency> all() { return new ArrayList<>(rows); }
        @Override public synchronized void markNotified(String id, String on, Instant at) {
            rows.replaceAll(d -> d.taskId().equals(id) && d.dependsOn().equals(on)
                    ? new TaskDependency(id, on, d.createdAt(), at) : d);
        }
        @Override public synchronized void clear(String id) { rows.removeIf(d -> d.taskId().equals(id)); }
    }
}
