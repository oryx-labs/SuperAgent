package com.superagent.web.task;

import com.superagent.api.chat.MessageDto;
import com.superagent.api.task.ArtifactDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskEventDto;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs document-driven tasks: understand the brief, execute it, pause for
 * confirmation if the model flags a blocking decision, and settle at a terminal
 * status. Execution happens on a background daemon pool (tasks run for minutes to
 * hours), so the detail view follows along by polling the persisted event stream
 * rather than holding a live connection.
 *
 * <p>The execution stream and steering messages are stored in the shared agent-run
 * tables via {@link ChatStore}, keyed by the task id — the same id passed to the
 * agent as its session id, so a resume turn continues the same conversation.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** The model emits this marker (per the wrapped brief) when it needs the user to decide before continuing. */
    static final String CONFIRM_MARKER = "NEEDS_CONFIRMATION";

    private final TaskStore tasks;
    private final ChatStore runStore;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final Map<String, Cancellation> activeRuns = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-run");
        t.setDaemon(true);
        return t;
    });

    private final Path workspaceRoot;
    private final String defaultModel;
    private final List<String> allowedTools;
    private final boolean skipPermissions;
    private final String defaultOwner;

    public TaskService(
            TaskStore tasks,
            ChatStore runStore,
            List<AgentRunner> agentRunners,
            @Value("${superagent.task.workspace-dir:./data/tasks}") String workspaceDir,
            @Value("${superagent.agent.default-model:}") String defaultModel,
            @Value("${superagent.task.allowed-tools:}") String allowedTools,
            @Value("${superagent.task.skip-permissions:false}") boolean skipPermissions,
            @Value("${superagent.task.default-owner:local}") String defaultOwner) {
        this.tasks = tasks;
        this.runStore = runStore;
        for (AgentRunner runner : agentRunners) {
            this.runners.put(runner.type(), runner);
        }
        this.workspaceRoot = Path.of(workspaceDir).toAbsolutePath().normalize();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? null : defaultModel.trim();
        this.allowedTools = parseTools(allowedTools);
        this.skipPermissions = skipPermissions;
        this.defaultOwner = defaultOwner;
        if (skipPermissions) {
            log.warn("superagent.task.skip-permissions=true: tasks run the agent WITHOUT tool-permission prompts "
                    + "(arbitrary file/command execution). Only enable this inside a real isolation boundary "
                    + "(per-task container/VM, no host credentials, restricted network).");
        }
        log.info("TaskService ready: runners={}, workspaceRoot={}, allowedTools={}, skipPermissions={}",
                runners.keySet(), workspaceRoot, this.allowedTools, skipPermissions);
    }

    private static List<String> parseTools(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : csv.split("[,\\s]+")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return List.copyOf(out);
    }

    // --- queries ---

    public List<TaskDto> list() {
        return tasks.listTasks().stream()
                .filter(t -> t.status() != TaskStatus.DELETED)
                .map(TaskService::toDto).toList();
    }

    public TaskDetailDto detail(String id) {
        Task task = require(id);
        List<MessageDto> messages = runStore.listMessages(id).stream().map(TaskService::toMessageDto).toList();
        List<TaskEventDto> events = runStore.listEvents(id).stream().map(TaskService::toEventDto).toList();
        return new TaskDetailDto(toDto(task), task.brief(), messages, events);
    }

    /** Events from {@code offset} onward (chronological), for incremental polling of a running task. */
    public List<TaskEventDto> eventsFrom(String id, int offset) {
        require(id);
        List<AgentEvent> all = runStore.listEvents(id);
        int from = Math.max(0, offset);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, all.size()).stream().map(TaskService::toEventDto).toList();
    }

    /** Lightweight status/progress snapshot for polling, without the event stream. */
    public TaskDto status(String id) {
        return toDto(require(id));
    }

    /** Top-level files in the task workspace, offered as downloadable artifacts. */
    public List<ArtifactDto> artifacts(String id) {
        Task task = require(id);
        if (task.workspaceDir() == null) {
            return List.of();
        }
        Path dir = Path.of(task.workspaceDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> new ArtifactDto(p.getFileName().toString(), sizeOf(p)))
                    .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Reads one artifact by name, guarding against path traversal out of the workspace. */
    public byte[] readArtifact(String id, String name) {
        Task task = require(id);
        if (task.workspaceDir() == null) {
            throw new ArtifactNotFoundException(name);
        }
        Path dir = Path.of(task.workspaceDir()).toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new ArtifactNotFoundException(name);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ArtifactNotFoundException(name);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    // --- lifecycle ---

    public TaskDto create(CreateTaskRequest request) {
        String id = UUID.randomUUID().toString();
        AgentType agentType = AgentType.fromString(request.agentType());
        TaskSource source = parseSource(request.source());
        Path workspace = workspaceRoot.resolve(id);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create workspace for task " + id, e);
        }
        Instant now = Instant.now();
        Task task = new Task(id, cleanTitle(request.title()), blankToNull(request.brief()), source,
                blankToNull(request.repo()), blankToNull(request.branch()), blankToNull(request.flow()),
                TaskStatus.PENDING, 0, BigDecimal.ZERO, blankToNull(request.model()), agentType,
                workspace.toString(), defaultOwner, null, now, null, null, now);
        tasks.save(task);
        log.info("created task {} ({})", id, task.title());
        startExecution(task);
        return toDto(task);
    }

    /** Steers a non-running task: answers a confirmation or follows up on a finished run; resumes the agent session. */
    public void sendMessage(String id, String content) {
        Task task = require(id);
        if (task.status() == TaskStatus.RUNNING) {
            throw new TaskBusyException(id);
        }
        runStore.appendMessage(new ChatMessage(UUID.randomUUID().toString(), id,
                MessageRole.USER, content, Instant.now()));
        Task running = task.withStatus(TaskStatus.RUNNING);
        tasks.save(running);
        executor.execute(() -> runTurn(running, content, false));
    }

    public void stop(String id) {
        require(id);
        Cancellation cancellation = activeRuns.get(id);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    /** Soft delete: marks the task DELETED so it drops out of the list; the row is kept. */
    public void delete(String id) {
        Task task = require(id);
        Cancellation cancellation = activeRuns.get(id);
        if (cancellation != null) {
            cancellation.cancel();
        }
        tasks.save(task.withStatus(TaskStatus.DELETED));
    }

    private void startExecution(Task task) {
        Task running = task.started();
        tasks.save(running);
        executor.execute(() -> runTurn(running, task.brief(), true));
    }

    private void runTurn(Task task, String prompt, boolean firstTurn) {
        AgentRunner runner = runners.get(task.agentType());
        if (runner == null) {
            settle(task, TaskStatus.ERROR, task.progress(), "No runner for agent type " + task.agentType(),
                    BigDecimal.ZERO);
            return;
        }

        String messageId = UUID.randomUUID().toString();
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<BigDecimal> turnCost = new AtomicReference<>(BigDecimal.ZERO);
        AtomicInteger progress = new AtomicInteger(Math.max(task.progress(), 10));
        Cancellation cancellation = new Cancellation();
        activeRuns.put(task.id(), cancellation);

        AgentEventSink sink = event -> {
            if (event.type() == AgentEventType.USAGE) {
                try { turnCost.set(new BigDecimal(event.payload())); } catch (RuntimeException ignore) { /* keep 0 */ }
                return; // cost metadata, not part of the visible output stream
            }
            runStore.appendEvent(new AgentEvent(task.id(), messageId, event.seq(),
                    event.type(), event.payload(), event.createdAt()));
            switch (event.type()) {
                case TEXT -> text.append(event.payload());
                case RESULT -> result.set(event.payload());
                case TOOL_USE -> nudgeProgress(task, progress);
                default -> { /* thinking / system / tool_result / error already persisted */ }
            }
        };

        try {
            AgentRunRequest request = new AgentRunRequest(
                    task.id(), firstTurn ? null : task.id(), task.workspaceDir(),
                    firstTurn ? wrapBrief(prompt) : prompt,
                    task.model() != null ? task.model() : defaultModel,
                    allowedTools, skipPermissions, null);
            runner.run(request, sink, cancellation);

            String finalText = result.get() != null ? result.get() : text.toString();
            runStore.appendMessage(new ChatMessage(messageId, task.id(),
                    MessageRole.ASSISTANT, finalText, Instant.now()));

            if (cancellation.isCancelled()) {
                settle(task, TaskStatus.STOPPED, progress.get(), null, turnCost.get());
            } else if (finalText != null && finalText.contains(CONFIRM_MARKER)) {
                settle(task, TaskStatus.NEEDS_CONFIRMATION, 50, null, turnCost.get());
            } else {
                writeOutput(task, finalText);
                settle(task, TaskStatus.DONE, 100, null, turnCost.get());
            }
        } catch (Exception e) {
            log.error("task {} failed", task.id(), e);
            String detail = e.getMessage() != null ? e.getMessage() : "task run failed";
            runStore.appendEvent(AgentEvent.of(task.id(), Long.MAX_VALUE, AgentEventType.ERROR, detail));
            settle(task, TaskStatus.ERROR, progress.get(), detail, turnCost.get());
        } finally {
            activeRuns.remove(task.id(), cancellation);
        }
    }

    /** Settles to a terminal status, or pauses at NEEDS_CONFIRMATION (which keeps the task open for a reply). */
    private void settle(Task task, TaskStatus status, int progress, String error, BigDecimal turnCost) {
        Task latest = tasks.findTask(task.id()).orElse(task);
        Task next = status == TaskStatus.NEEDS_CONFIRMATION
                ? latest.withStatus(TaskStatus.NEEDS_CONFIRMATION).withProgress(progress)
                : latest.finished(status, progress, error);
        BigDecimal spend = (latest.spend() == null ? BigDecimal.ZERO : latest.spend())
                .add(turnCost == null ? BigDecimal.ZERO : turnCost);
        tasks.save(next.withSpend(spend));
        log.info("task {} -> {} ({}%), spend={}", task.id(), status, next.progress(), spend);
    }

    private void nudgeProgress(Task task, AtomicInteger progress) {
        int current = progress.get();
        int next = Math.min(90, current + 5);
        progress.set(next);
        if (next / 10 != current / 10) { // persist only when a 10% bucket is crossed, to keep writes light
            tasks.findTask(task.id()).ifPresent(t -> {
                if (t.status() == TaskStatus.RUNNING) {
                    tasks.save(t.withProgress(next));
                }
            });
        }
    }

    /** Best-effort delivery artifact so the final result lands on disk in the task workspace. */
    private void writeOutput(Task task, String finalText) {
        if (finalText == null || finalText.isBlank() || task.workspaceDir() == null) {
            return;
        }
        try {
            Files.writeString(Path.of(task.workspaceDir(), "output.md"), finalText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("could not write output.md for task {}: {}", task.id(), e.getMessage());
        }
    }

    private String wrapBrief(String brief) {
        return """
                你在执行一个任务。请先理解下面的任务说明，然后完成它：实现，并自行验证（例如运行测试 / 构建）。
                如果说明存在歧义，或有需要用户拍板才能继续的关键决策，不要擅自假设——请输出一行以 \
                """ + CONFIRM_MARKER + """
                : 开头、后接你的具体问题，然后停止等待用户答复。否则请直接完成，并在结尾给出交付总结。

                任务说明：
                """ + (brief == null ? "" : brief);
    }

    private Task require(String id) {
        return tasks.findTask(id)
                .filter(t -> t.status() != TaskStatus.DELETED)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    // --- mapping / parsing ---

    private static TaskSource parseSource(String value) {
        return value == null || value.isBlank()
                ? TaskSource.TASK_FORM : TaskSource.valueOf(value.trim().toUpperCase());
    }

    private static String cleanTitle(String title) {
        String clean = title == null ? "" : title.trim();
        return clean.isEmpty() ? "Untitled task" : clean;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static TaskDto toDto(Task t) {
        return new TaskDto(t.id(), t.title(), t.source().name().toLowerCase(),
                t.repo(), t.branch(), t.flow(), t.status().name().toLowerCase(),
                t.progress(), t.spend(), t.model(), t.createdBy(), t.error(),
                t.createdAt(), t.startedAt(), t.finishedAt(), t.updatedAt());
    }

    private static MessageDto toMessageDto(ChatMessage m) {
        return new MessageDto(m.id(), m.role().name().toLowerCase(), m.content(), m.createdAt());
    }

    private static TaskEventDto toEventDto(AgentEvent e) {
        return new TaskEventDto(e.type().name().toLowerCase(), e.seq(), e.payload(), e.createdAt());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
