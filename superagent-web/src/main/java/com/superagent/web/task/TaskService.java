package com.superagent.web.task;

import com.superagent.api.ApiPaths;
import com.superagent.api.chat.ImageUploadDto;
import com.superagent.api.chat.MessageDto;
import com.superagent.api.task.ArtifactDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskEventDto;
import com.superagent.api.task.TaskPageDto;
import com.superagent.api.task.TaskPhaseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskKind;
import com.superagent.core.task.TaskKindClassifier;
import com.superagent.core.task.TaskPhase;
import com.superagent.core.task.TaskPhaseStatus;
import com.superagent.core.task.TaskPhaseStore;
import com.superagent.core.task.TaskRunState;
import com.superagent.core.task.TaskRunStateStore;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import jakarta.annotation.PreDestroy;
import com.superagent.web.attachment.ImageAttachmentStore;
import com.superagent.web.mcp.McpGrants;
import com.superagent.web.skill.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
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
public class TaskService implements TaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** The model emits this marker (per the wrapped brief) when it needs the user to decide before continuing. */
    static final String CONFIRM_MARKER = "NEEDS_CONFIRMATION";

    // The fixed coding flow: PRD → develop → verify, with real progress at each boundary.
    private static final String PHASE_PRD = "prd";
    private static final String PHASE_DEV = "dev";
    private static final String PHASE_ACCEPT = "accept";
    private static final int[] PHASE_DONE_PROGRESS = {33, 66, 100};

    /** Record-aware (parameter-names module) mapper for the phase JSON persisted via {@link TaskPhaseStore}. */
    private static final ObjectMapper PHASE_JSON = new ObjectMapper().findAndRegisterModules();

    private static final String PRD_RETRY_PROMPT = """
            上一步没有检测到 PRD 内容。请立刻输出一份 PRD（Markdown：## 背景 / ## 需求 / ## 技术思路）作为回复，\
            不要写文件，也不要只回复确认语。""";

    private final TaskStore tasks;
    private final TaskPhaseStore phaseStore;
    private final TaskRunStateStore runStates;
    private final ChatStore runStore;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final Map<String, Cancellation> activeRuns = new ConcurrentHashMap<>();
    /**
     * Images attached to a steering message, handed to the next turn this task runs (the one that
     * carries that message) and then dropped, so later phase turns don't re-send them. A side
     * channel rather than a parameter because a steering message can wait in the queue and then
     * enter any of the run flows; after a restart it is rebuilt from the stored message.
     */
    private final Map<String, List<ImageAttachment>> nextTurnImages = new ConcurrentHashMap<>();
    private final ImageAttachmentStore images;
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
    private final McpGrants mcpGrants;   // null = task runs get no knowledge-base tools
    private final SkillService skills;   // null = tasks run without skills (tests)
    private final String codingFlowTemplate = loadCodingFlowTemplate();

    // Session reuse + per-owner parallelism. All pool/queue access is under schedulerLock.
    private final TaskSessionPool pool;
    private final Object schedulerLock = new Object();
    private final Map<String, Deque<QueuedRun>> queues = new HashMap<>();

    /**
     * A task run waiting for a free session. {@code sessionId} pins a steering run to the
     * task's own session (null = any session, for a task's first run).
     */
    private record QueuedRun(String taskId, String prompt, boolean wrapBrief, String sessionId) {
    }

    public TaskService(
            TaskStore tasks,
            TaskPhaseStore phaseStore,
            TaskRunStateStore runStates,
            ChatStore runStore,
            List<AgentRunner> agentRunners,
            ImageAttachmentStore images,
            @Value("${superagent.task.workspace-dir:./data/tasks}") String workspaceDir,
            @Value("${superagent.agent.default-model:}") String defaultModel,
            @Value("${superagent.task.allowed-tools:}") String allowedTools,
            @Value("${superagent.task.skip-permissions:false}") boolean skipPermissions,
            @Value("${superagent.task.max-parallel:50}") int maxParallel,
            @Value("${superagent.task.default-owner:local}") String defaultOwner,
            McpGrants mcpGrants,
            SkillService skills) {
        this.tasks = tasks;
        this.phaseStore = phaseStore;
        this.runStates = runStates;
        this.runStore = runStore;
        this.images = images;
        for (AgentRunner runner : agentRunners) {
            this.runners.put(runner.type(), runner);
        }
        this.workspaceRoot = Path.of(workspaceDir).toAbsolutePath().normalize();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? null : defaultModel.trim();
        this.allowedTools = parseTools(allowedTools);
        this.skipPermissions = skipPermissions;
        this.defaultOwner = defaultOwner;
        this.mcpGrants = mcpGrants != null && mcpGrants.available() ? mcpGrants : null;
        this.skills = skills;
        this.pool = new TaskSessionPool(maxParallel, workspaceRoot);
        if (skipPermissions) {
            log.warn("superagent.task.skip-permissions=true: tasks run the agent WITHOUT tool-permission prompts "
                    + "(arbitrary file/command execution). Only enable this inside a real isolation boundary "
                    + "(per-task container/VM, no host credentials, restricted network).");
        }
        log.info("TaskService ready: runners={}, workspaceRoot={}, allowedTools={}, skipPermissions={}, maxParallel/owner={}",
                runners.keySet(), workspaceRoot, this.allowedTools, skipPermissions, maxParallel);
        restoreSessions();
    }

    /**
     * The pool lives in memory, so without this every restart would start empty and the
     * next task would mint a new session even though finished tasks left reusable ones on
     * disk. Sessions of tasks waiting on a confirmation stay reserved for them; sessions of
     * RUNNING tasks are left to the watchdog, which resumes those tasks on them.
     */
    private void restoreSessions() {
        List<Task> all = tasks.listTasks().stream().filter(t -> t.workspaceDir() != null).toList();
        Set<String> running = new java.util.HashSet<>();
        all.stream().filter(t -> t.status() == TaskStatus.RUNNING).forEach(t -> running.add(boundSessionId(t)));
        int restored = 0;
        synchronized (schedulerLock) {
            for (Task t : all) {
                String sessionId = boundSessionId(t);
                if (t.status() != TaskStatus.RUNNING && t.status() != TaskStatus.NEEDS_CONFIRMATION
                        && !running.contains(sessionId)) {
                    pool.restore(t.createdBy(), t.agentType(), sessionId, t.workspaceDir(), null);
                    restored++;
                }
            }
            for (Task t : all) {
                if (t.status() == TaskStatus.NEEDS_CONFIRMATION) {
                    pool.restore(t.createdBy(), t.agentType(), boundSessionId(t), t.workspaceDir(), t.id());
                }
            }
        }
        if (restored > 0) {
            log.info("restored idle task sessions from {} finished task(s)", restored);
        }
        requeueWaitingSteering(all);
    }

    /**
     * A steering message that was waiting for its session when the process stopped: the queue
     * lived in memory, so without this the task would sit at PENDING forever. The message is
     * already stored, so it is re-queued from there and dispatched.
     */
    private void requeueWaitingSteering(List<Task> started) {
        Set<String> owners = new java.util.HashSet<>();
        synchronized (schedulerLock) {
            for (Task t : started) {
                if (t.status() != TaskStatus.PENDING) {
                    continue;
                }
                ChatMessage last = lastUserMessage(t.id());
                if (last == null) {
                    tasks.save(t.withStatus(TaskStatus.STOPPED)); // nothing to resume with
                    continue;
                }
                List<ImageAttachment> attached = images.list(t.id(), last.id());
                if (!attached.isEmpty()) {
                    nextTurnImages.put(t.id(), attached);
                }
                String content = agentPrompt(last.content(), attached);
                queues.computeIfAbsent(t.createdBy(), k -> new ArrayDeque<>())
                        .addLast(new QueuedRun(t.id(), content, false, boundSessionId(t)));
                owners.add(t.createdBy());
                log.info("re-queued waiting steering message for task {}", t.id());
            }
        }
        owners.forEach(this::tryDispatch);
        // New tasks still waiting for their first session were queued in memory too.
        tasks.listTasks().stream()
                .filter(t -> t.status() == TaskStatus.PENDING && t.workspaceDir() == null)
                .forEach(this::schedule);
    }

    private ChatMessage lastUserMessage(String taskId) {
        List<ChatMessage> messages = runStore.listMessages(taskId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == MessageRole.USER) {
                return messages.get(i);
            }
        }
        return null;
    }

    /** Agent CLIs that can actually run a task here (have a runner). */
    public Set<AgentType> runnableAgents() {
        return Set.copyOf(runners.keySet());
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
                .map(this::toListDto).toList();
    }

    /** One page of the list, newest first; a page past the end is clamped to the last one. */
    public TaskPageDto page(int page, int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        List<Task> live = tasks.listTasks().stream()
                .filter(t -> t.status() != TaskStatus.DELETED)
                .sorted(Comparator.comparing(Task::createdAt).reversed())
                .toList();
        int pages = Math.max(1, (live.size() + pageSize - 1) / pageSize);
        int current = Math.min(Math.max(1, page), pages);
        List<TaskDto> items = live.stream().skip((long) (current - 1) * pageSize).limit(pageSize)
                .map(this::toListDto).toList();
        return new TaskPageDto(items, live.size(), current, pageSize);
    }

    public TaskDetailDto detail(String id) {
        Task task = require(id);
        List<MessageDto> messages = runStore.listMessages(id).stream().map(m -> toMessageDto(id, m)).toList();
        List<TaskEventDto> events = runStore.listEvents(id).stream().map(TaskService::toEventDto).toList();
        List<TaskPhaseDto> phases = TaskKind.fromStorage(task.flow()) == TaskKind.CODING
                ? loadPhases(id).stream().map(TaskService::toPhaseDto).toList()
                : List.of();
        return new TaskDetailDto(toDto(task), task.brief(), messages, events, phases);
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
        Path file = artifactFile(id, name);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ArtifactNotFoundException(name);
        }
    }

    /** An artifact's path, confined to the task workspace (no traversal, no symlink out of it). */
    public Path artifactFile(String id, String name) {
        Task task = require(id);
        if (task.workspaceDir() == null || name == null) {
            throw new ArtifactNotFoundException(name);
        }
        Path dir = Path.of(task.workspaceDir()).toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactNotFoundException(name);
        }
        return file;
    }

    /**
     * "Save the main output to the knowledge base" is carried as an instruction in the brief: the
     * agent does the save with its knowledge tools, and the request stays visible in the Prompt tab.
     */
    private static String withKnowledgeNote(String brief, CreateTaskRequest request) {
        if (!Boolean.TRUE.equals(request.saveToKnowledge())) {
            return brief;
        }
        // Blank folder = the agent decides; "/" = the root.
        String raw = request.knowledgeFolder() == null ? "" : request.knowledgeFolder().strip();
        String folder = raw.replaceAll("^/+|/+$", "");
        String where = raw.isEmpty()
                ? "根据内容选择合适的目录（先用 knowledge_list_folders 查看已有目录，没有合适的就用 knowledge_create_folder 新建）"
                : folder.isEmpty() ? "根目录" : "「" + folder + "」目录";
        String note = "完成后：用 knowledge_save 把本任务的主要产出文件保存到知识库的" + where + "，并在最终回复里写明保存位置。";
        return (brief == null ? "" : brief + "\n\n") + "---\n" + note;
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
        return create(request, null);
    }

    public TaskDto create(CreateTaskRequest request, String owner) {
        return create(request, owner, null);
    }

    /**
     * {@code owner} is the requesting user (null = the default owner); their knowledge base receives saves.
     * {@code agentId} is the agent creating it, whose skills the task then uses (null = all skills).
     */
    public TaskDto create(CreateTaskRequest request, String owner, String agentId) {
        String id = UUID.randomUUID().toString();
        AgentType agentType = AgentType.fromString(request.agentType());
        if (!runners.containsKey(agentType)) {
            // Fail at creation rather than letting the task start and error with "no runner".
            throw new IllegalArgumentException("Agent " + agentType.cliName() + " can't run tasks here yet; available: "
                    + runners.keySet().stream().map(AgentType::cliName).sorted().toList());
        }
        TaskSource source = parseSource(request.source());
        TaskKind kind = resolveKind(request);
        Instant now = Instant.now();
        // No workspace yet: it comes from the session this task is scheduled onto.
        // The kind is stored in the (otherwise unused) flow column — zero migration.
        Task task = new Task(id, cleanTitle(request.title()), withKnowledgeNote(blankToNull(request.brief()), request),
                source,
                blankToNull(request.repo()), blankToNull(request.branch()), kind.storageName(),
                TaskStatus.PENDING, 0, BigDecimal.ZERO, blankToNull(request.model()), agentType,
                null, owner == null || owner.isBlank() ? defaultOwner : owner, null, now, null, null, now);
        tasks.save(task);
        if (kind == TaskKind.CODING) {
            persistPhases(id, initialCodingPhases());
        }
        if (agentId != null && skills != null) {
            skills.bindTaskToAgent(id, agentId); // before scheduling, so the first turn already has them
        }
        log.info("created task {} ({}), kind={}", id, task.title(), kind.storageName());
        schedule(task);
        return toDto(task);
    }

    /** Explicit kind wins; "auto"/blank falls back to the keyword classifier. */
    private static TaskKind resolveKind(CreateTaskRequest request) {
        String k = request.kind();
        if (k != null && !k.isBlank() && !k.equalsIgnoreCase("auto")) {
            return TaskKind.fromStorage(k);
        }
        return TaskKindClassifier.classify(request.title(), request.brief());
    }

    /**
     * Schedules a task onto a session: reuse an idle session (resuming its context),
     * else a new one under the per-owner parallel cap, else queue until a slot frees.
     */
    private void schedule(Task task) {
        synchronized (schedulerLock) {
            TaskSessionPool.Lease lease = pool.acquire(task.createdBy(), task.agentType(), task.id());
            if (lease != null) {
                assignAndRun(task, lease, task.brief(), true);
            } else {
                queues.computeIfAbsent(task.createdBy(), k -> new ArrayDeque<>())
                        .addLast(new QueuedRun(task.id(), task.brief(), true, null));
                log.info("task {} queued (owner {} at parallel cap)", task.id(), task.createdBy());
            }
        }
    }

    /** Binds the task to the leased session's workspace, marks it RUNNING, and runs the turn. Call under schedulerLock. */
    private void assignAndRun(Task task, TaskSessionPool.Lease lease, String prompt, boolean wrapBrief) {
        TaskSessionPool.Session session = lease.session();
        Task running = task.withWorkspaceDir(session.workspaceDir).started();
        tasks.save(running);
        executor.execute(() -> run(running, prompt, session.id, lease.firstTurn(), wrapBrief));
    }

    /** When a session frees up, run every queued task for that owner that can now get its session. */
    private void tryDispatch(String owner) {
        synchronized (schedulerLock) {
            Deque<QueuedRun> q = queues.get(owner);
            if (q == null) {
                return;
            }
            for (java.util.Iterator<QueuedRun> it = q.iterator(); it.hasNext(); ) {
                QueuedRun run = it.next();
                Task task = tasks.findTask(run.taskId())
                        .filter(t -> t.status() != TaskStatus.DELETED).orElse(null);
                if (task == null) {
                    it.remove();
                    continue;
                }
                if (run.sessionId() != null) {
                    // Pinned steering run: only its own session will do; skip it while that session is busy.
                    TaskSessionPool.Lease lease =
                            pool.acquireSession(owner, task.agentType(), task.id(), run.sessionId(), task.workspaceDir());
                    if (lease != null) {
                        it.remove();
                        resumeRun(task, lease, run.prompt());
                    }
                } else {
                    TaskSessionPool.Lease lease = pool.acquire(owner, task.agentType(), run.taskId());
                    if (lease == null) {
                        continue; // no idle session of this CLI and at the cap; another CLI's run may still fit
                    }
                    it.remove();
                    assignAndRun(task, lease, run.prompt(), run.wrapBrief());
                }
            }
        }
    }

    /**
     * Steers a task that isn't running: answers a confirmation or follows up on a finished
     * or failed run. Always continues in the task's OWN agent session (same conversation
     * via {@code --resume}, same workspace) — never a fresh or borrowed one; if another task
     * currently holds that session, the steering run queues until it frees.
     */
    public void sendMessage(String id, String content) {
        sendMessage(id, content, List.of());
    }

    public void sendMessage(String id, String text, List<ImageUploadDto> uploads) {
        Task task = require(id);
        if (task.status() == TaskStatus.RUNNING || task.workspaceDir() == null) {
            throw new TaskBusyException(id); // running, or queued and never started
        }
        String sessionId = boundSessionId(task);
        synchronized (schedulerLock) {
            Deque<QueuedRun> q = queues.get(task.createdBy());
            if (q != null && q.stream().anyMatch(r -> r.taskId().equals(id))) {
                throw new TaskBusyException(id); // a steering run is already waiting for this session
            }
            String messageId = UUID.randomUUID().toString();
            List<ImageAttachment> attached = images.save(id, messageId, uploads);
            runStore.appendMessage(new ChatMessage(messageId, id,
                    MessageRole.USER, text == null ? "" : text, Instant.now()));
            if (!attached.isEmpty()) {
                nextTurnImages.put(id, attached);
            }
            String content = agentPrompt(text, attached);
            TaskSessionPool.Lease lease = pool.acquireSession(task.createdBy(), task.agentType(), id, sessionId, task.workspaceDir());
            if (lease != null) {
                resumeRun(task, lease, content);
            } else {
                // PENDING while waiting; if a restart drops the in-memory queue, the check above
                // no longer finds it and the user can simply send again.
                tasks.save(task.withStatus(TaskStatus.PENDING));
                queues.computeIfAbsent(task.createdBy(), k -> new ArrayDeque<>())
                        .addLast(new QueuedRun(id, content, false, sessionId));
                log.info("task {} steering queued: its session {} is held by another task", id, sessionId);
            }
        }
    }

    /** The prompt to run for a user message: an image-only message still needs some text for the CLI. */
    private static String agentPrompt(String text, List<ImageAttachment> attached) {
        boolean blank = text == null || text.isBlank();
        return blank && !attached.isEmpty() ? ImageAttachmentStore.IMAGE_ONLY_PROMPT : (text == null ? "" : text);
    }

    /** Continues a task in the leased session, keeping its workspace and start time. Call under schedulerLock. */
    private void resumeRun(Task task, TaskSessionPool.Lease lease, String prompt) {
        Task running = task.withStatus(TaskStatus.RUNNING);
        tasks.save(running);
        String sessionId = lease.session().id;
        boolean firstTurn = lease.firstTurn();
        executor.execute(() -> run(running, prompt, sessionId, firstTurn, false));
    }

    /**
     * The task's chosen model, else the configured default — which is a Claude model name
     * (shared with chat), so it only applies to Claude; other CLIs fall back to their own default.
     */
    private String effectiveModel(Task task) {
        if (task.model() != null) {
            return task.model();
        }
        return task.agentType() == AgentType.CLAUDE ? defaultModel : null;
    }

    /**
     * The agent session a task runs in. A watchdog rotation records the new id in run-state;
     * otherwise it is the session the task was first scheduled on, whose workspace directory
     * is named after it (see TaskSessionPool).
     */
    private String boundSessionId(Task task) {
        return runStates.find(task.id())
                .map(TaskRunState::agentSessionId)
                .filter(s -> s != null && !s.isBlank())
                .orElseGet(() -> deriveSessionId(task));
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
            cancellation.cancel();   // a running turn will settle to STOPPED and release its session
        }
        synchronized (schedulerLock) {
            Deque<QueuedRun> q = queues.get(task.createdBy());
            if (q != null) {
                q.removeIf(r -> r.taskId().equals(id));
            }
            TaskSessionPool.Session session = pool.sessionForTask(id);
            if (session != null && task.status() != TaskStatus.RUNNING) {
                pool.release(session);   // paused/idle binding: free it now
            }
            pool.dropReservation(id);    // a reservation it held while another task ran in the session
        }
        nextTurnImages.remove(id);
        tasks.save(task.withStatus(TaskStatus.DELETED));
        tryDispatch(task.createdBy());
    }

    // --- resilience (TaskRecovery, driven by the watchdog) ---

    /** Rotate to a fresh session after this many consecutive failures on one session. */
    private static final int ROTATE_AFTER = 3;
    /** Overall retry budget before a task is given up for good. */
    private static final int MAX_ATTEMPTS = 10;

    @Override
    public Set<String> activeTaskIds() {
        return Set.copyOf(activeRuns.keySet());
    }

    @Override
    public void recover(String id) {
        Task task = tasks.findTask(id).orElse(null);
        if (task == null || (task.status() != TaskStatus.RUNNING && task.status() != TaskStatus.ERROR)) {
            return;
        }
        if (activeRuns.containsKey(id)) {
            return; // actually executing here; healthy
        }

        TaskRunState state = runStates.find(id).orElseGet(() -> TaskRunState.initial(id, deriveSessionId(task)));
        if (state.attemptCount() >= MAX_ATTEMPTS) {
            if (task.status() != TaskStatus.ERROR) {
                tasks.save(task.finished(TaskStatus.ERROR, task.progress(),
                        "retry budget exhausted after " + MAX_ATTEMPTS + " attempts"));
            }
            log.warn("task {} given up after {} attempts", id, state.attemptCount());
            return;
        }

        boolean failure = task.status() == TaskStatus.ERROR;
        boolean rotate = failure && state.failureCount() + 1 >= ROTATE_AFTER;

        String sessionId;
        boolean firstTurn;
        synchronized (schedulerLock) {
            TaskSessionPool.Session bound = pool.sessionForTask(id);
            if (bound != null && !rotate) {
                pool.resumeBusy(bound);           // same-process stall: reuse the still-bound session
                sessionId = bound.id;
                firstTurn = false;
            } else if (rotate) {
                sessionId = UUID.randomUUID().toString();   // fresh conversation, same workspace (keep prior work)
                firstTurn = true;
                pool.adopt(task.createdBy(), task.agentType(), id, sessionId, task.workspaceDir(), false);
            } else {
                sessionId = deriveSessionId(task);  // restart: pool is empty, resume the persisted agent session
                firstTurn = false;
                pool.adopt(task.createdBy(), task.agentType(), id, sessionId, task.workspaceDir(), true);
            }
        }

        TaskRunState next = rotate ? state.rotatedTo(sessionId)
                : failure ? state.failedOnSameSession() : state.resumed();
        runStates.save(next);

        Task running = task.withStatus(TaskStatus.RUNNING);
        tasks.save(running);
        String prompt = recoverPrompt(rotate);
        String sid = sessionId;
        boolean ft = firstTurn;
        log.info("recovering task {} (attempt {}, failures {}, rotate={}, session {})",
                id, next.attemptCount(), next.failureCount(), rotate, sid);
        executor.execute(() -> run(running, prompt, sid, ft, false));
    }

    /** The pool session id equals the workspace dir's name (see TaskSessionPool), so it is recoverable without extra state. */
    private String deriveSessionId(Task task) {
        if (task.workspaceDir() == null || task.workspaceDir().isBlank()) {
            return UUID.randomUUID().toString();
        }
        return Path.of(task.workspaceDir()).getFileName().toString();
    }

    private static String recoverPrompt(boolean rotate) {
        return rotate
                ? "上一个会话连续失败。工作区里可能已有部分成果，请基于现有文件重新评估并完成本任务，然后给出交付总结。"
                : "服务刚恢复，或上一次执行被中断。请检查工作区已有成果，从中断处继续完成本任务；若其实已完成，请做收尾并给出交付总结。";
    }

    /** Dispatches a scheduled turn: coding tasks run the fixed PRD→develop→verify flow, others run single-pass. */
    private void run(Task task, String prompt, String agentSessionId, boolean sessionFirstTurn, boolean wrapBriefPrompt) {
        Cancellation cancellation = new Cancellation();
        activeRuns.put(task.id(), cancellation);
        try {
            boolean coding = TaskKind.fromStorage(task.flow()) == TaskKind.CODING;
            if (coding && !wrapBriefPrompt && currentPhaseIndex(loadPhases(task.id())) >= loadPhases(task.id()).size()) {
                // Rework on a finished coding task: every phase is done, so there's no phase to resume —
                // without this the message would settle DONE without the agent ever seeing it.
                runFollowUp(task, prompt, agentSessionId, sessionFirstTurn, cancellation);
            } else if (coding) {
                // wrapBrief=true marks the initial execution (run phases from the start); false is a
                // post-confirmation resume carrying the user's reply for the current phase.
                runCoding(task, wrapBriefPrompt ? null : prompt, agentSessionId, sessionFirstTurn, cancellation);
            } else {
                runTurn(task, prompt, agentSessionId, sessionFirstTurn, wrapBriefPrompt, cancellation);
            }
        } finally {
            activeRuns.remove(task.id(), cancellation);
        }
    }

    private record TurnResult(String finalText, BigDecimal cost, boolean cancelled) {
        boolean needsConfirmation() {
            // Only a real blocking question counts: a line that STARTS with the marker (per the wrapped
            // brief's instruction). A passing mention in a summary must not falsely pause a finished task.
            return finalText != null
                    && finalText.lines().anyMatch(line -> line.stripLeading().startsWith(CONFIRM_MARKER));
        }
    }

    /** Runs one agent turn, persisting its events/messages; returns the outcome without settling. */
    private TurnResult executeTurn(Task task, String prompt, String agentSessionId, boolean sessionFirstTurn,
                                   boolean wrapBriefPrompt, Runnable onToolUse, Cancellation cancellation) {
        AgentRunner runner = runners.get(task.agentType());
        if (runner == null) {
            throw new IllegalStateException("No runner for agent type " + task.agentType());
        }
        String messageId = UUID.randomUUID().toString();
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<BigDecimal> turnCost = new AtomicReference<>(BigDecimal.ZERO);
        AtomicReference<String> error = new AtomicReference<>();

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
                case TOOL_USE -> { if (onToolUse != null) onToolUse.run(); }
                case ERROR -> error.set(event.payload());
                default -> { /* thinking / system / tool_result already persisted */ }
            }
        };

        // agentSessionId is the pool session's id: --session-id on its first turn, --resume after,
        // so a reused session keeps its accumulated context. Events are re-keyed to task.id above.
        // A per-turn token gives this run the knowledge tools for its owner only, revoked after the turn.
        McpGrants.Grant grant = mcpGrants == null || task.workspaceDir() == null ? null
                : mcpGrants.openTask(task.createdBy(), task.id(), task.workspaceDir());
        List<String> tools = allowedTools;
        if (grant != null) {
            tools = new ArrayList<>(allowedTools);
            tools.addAll(McpGrants.KNOWLEDGE_TOOLS);
        }
        AgentRunRequest request = new AgentRunRequest(
                agentSessionId, sessionFirstTurn ? null : agentSessionId, task.workspaceDir(),
                wrapBriefPrompt ? wrapBrief(prompt) : prompt,
                effectiveModel(task),
                tools, skipPermissions, grant == null ? null : mcpGrants.mcpConfig(grant),
                skills == null ? null : skills.install(task.workspaceDir(), skills.forTask(task.id()), grant != null),
                nextTurnImages.remove(task.id()));
        try {
            runner.run(request, sink, cancellation);
        } finally {
            if (grant != null) {
                mcpGrants.close(grant);
            }
        }

        String finalText = result.get() != null ? result.get() : text.toString();
        if (!cancellation.isCancelled() && error.get() != null && finalText.isBlank()) {
            // The agent itself failed (couldn't start, couldn't resume, crashed) and produced nothing.
            // Fail the task with the real reason instead of letting a gate read the empty output as
            // "needs confirmation" — a reply can't fix an agent failure; ERROR lets the watchdog retry.
            throw new IllegalStateException("Agent run failed: " + (error.get().isBlank() ? "no output" : error.get()));
        }
        runStore.appendMessage(new ChatMessage(messageId, task.id(),
                MessageRole.ASSISTANT, finalText, Instant.now()));
        return new TurnResult(finalText, turnCost.get(), cancellation.isCancelled());
    }

    /** Single-pass execution for non-coding tasks: run the turn, then settle to a terminal/confirmation status. */
    /** One steering turn on a finished task; the agent's reply lands in the run log, the delivery doc is kept. */
    private void runFollowUp(Task task, String prompt, String agentSessionId, boolean sessionFirstTurn,
                             Cancellation cancellation) {
        TurnResult turn;
        try {
            turn = executeTurn(task, prompt, agentSessionId, sessionFirstTurn, false, null, cancellation);
        } catch (Exception e) {
            failTurn(task, task.progress(), e);
            return;
        }
        if (turn.cancelled()) {
            settle(task, TaskStatus.STOPPED, task.progress(), null, turn.cost());
        } else if (turn.needsConfirmation()) {
            settle(task, TaskStatus.NEEDS_CONFIRMATION, task.progress(), null, turn.cost());
        } else {
            settle(task, TaskStatus.DONE, 100, null, turn.cost());
        }
    }

    private void runTurn(Task task, String prompt, String agentSessionId, boolean sessionFirstTurn,
                         boolean wrapBriefPrompt, Cancellation cancellation) {
        AtomicInteger progress = new AtomicInteger(Math.max(task.progress(), 10));
        TurnResult turn;
        try {
            turn = executeTurn(task, prompt, agentSessionId, sessionFirstTurn, wrapBriefPrompt,
                    () -> nudgeProgress(task, progress), cancellation);
        } catch (Exception e) {
            failTurn(task, progress.get(), e);
            return;
        }
        if (turn.cancelled()) {
            settle(task, TaskStatus.STOPPED, progress.get(), null, turn.cost());
        } else if (turn.needsConfirmation()) {
            settle(task, TaskStatus.NEEDS_CONFIRMATION, 50, null, turn.cost());
        } else {
            writeOutput(task, turn.finalText());
            settle(task, TaskStatus.DONE, 100, null, turn.cost());
        }
    }

    /**
     * Backend-enforced coding flow: PRD → develop → verify, each a resumed turn on the
     * same session. Resumes at the first non-DONE phase, so a post-confirmation reply
     * (passed as {@code resumePrompt}) continues the phase it paused on. Gates: the PRD
     * step must produce PRD content (one auto-retry), and the verify step must produce
     * evidence, else the task pauses at NEEDS_CONFIRMATION with the reason.
     */
    private void runCoding(Task task, String resumePrompt, String agentSessionId,
                           boolean sessionFirstTurn, Cancellation cancellation) {
        List<TaskPhase> phases = new ArrayList<>(loadPhases(task.id()));
        int start = currentPhaseIndex(phases);
        boolean firstTurn = sessionFirstTurn;
        try {
            for (int i = start; i < phases.size(); i++) {
                TaskPhase phase = phases.get(i).withStatus(TaskPhaseStatus.RUNNING);
                phases.set(i, phase);
                persistPhases(task.id(), phases);
                persistProgress(task.id(), runningProgress(i));

                String prompt = (i == start && resumePrompt != null)
                        ? resumePrompt : phasePrompt(phase.key(), task.brief());
                TurnResult turn = executeTurn(task, prompt, agentSessionId, firstTurn, false, null, cancellation);
                firstTurn = false;
                addSpend(task.id(), turn.cost());

                if (turn.cancelled()) {
                    settle(task, TaskStatus.STOPPED, runningProgress(i), null, BigDecimal.ZERO);
                    return;
                }
                if (turn.needsConfirmation()) {
                    settle(task, TaskStatus.NEEDS_CONFIRMATION, runningProgress(i), null, BigDecimal.ZERO);
                    return; // phase stays RUNNING; a user reply resumes it here
                }

                if (PHASE_PRD.equals(phase.key())) {
                    String prd = firstNonBlank(turn.finalText());
                    if (prd == null) {
                        TurnResult retry = executeTurn(task, PRD_RETRY_PROMPT, agentSessionId, false, false, null, cancellation);
                        addSpend(task.id(), retry.cost());
                        if (retry.cancelled()) {
                            settle(task, TaskStatus.STOPPED, runningProgress(i), null, BigDecimal.ZERO);
                            return;
                        }
                        prd = firstNonBlank(retry.finalText());
                        if (prd == null) {
                            pauseForGate(task, phases, i, "第1步未产出 PRD");
                            return;
                        }
                    }
                    phase = phase.withContent(prd);
                } else if (PHASE_ACCEPT.equals(phase.key())) {
                    String evidence = firstNonBlank(turn.finalText());
                    if (evidence == null) {
                        pauseForGate(task, phases, i, "缺少验收证据");
                        return;
                    }
                    phase = phase.withEvidence(evidence);
                }

                phase = phase.withStatus(TaskPhaseStatus.DONE);
                phases.set(i, phase);
                persistPhases(task.id(), phases);
                persistProgress(task.id(), PHASE_DONE_PROGRESS[i]);
            }
        } catch (Exception e) {
            persistPhases(task.id(), phases);
            failTurn(task, task.progress(), e);
            return;
        }
        writeOutput(task, phases.get(phases.size() - 1).evidence());
        settle(task, TaskStatus.DONE, 100, null, BigDecimal.ZERO);
    }

    /** Pauses a coding task at NEEDS_CONFIRMATION, surfacing a gate reason into the visible stream. */
    private void pauseForGate(Task task, List<TaskPhase> phases, int phaseIndex, String reason) {
        persistPhases(task.id(), phases); // current phase stays RUNNING, so a reply resumes it
        String text = CONFIRM_MARKER + ": " + reason;
        runStore.appendEvent(AgentEvent.of(task.id(), Long.MAX_VALUE, AgentEventType.TEXT, text));
        runStore.appendMessage(new ChatMessage(UUID.randomUUID().toString(), task.id(),
                MessageRole.ASSISTANT, text, Instant.now()));
        settle(task, TaskStatus.NEEDS_CONFIRMATION, runningProgress(phaseIndex), null, BigDecimal.ZERO);
    }

    private void failTurn(Task task, int progress, Exception e) {
        log.error("task {} failed", task.id(), e);
        String detail = e.getMessage() != null ? e.getMessage() : "task run failed";
        runStore.appendEvent(AgentEvent.of(task.id(), Long.MAX_VALUE, AgentEventType.ERROR, detail));
        settle(task, TaskStatus.ERROR, progress, detail, BigDecimal.ZERO);
    }

    // --- coding phase helpers ---

    private static List<TaskPhase> initialCodingPhases() {
        return List.of(
                new TaskPhase(PHASE_PRD, "PRD", TaskPhaseStatus.PENDING, null, null),
                new TaskPhase(PHASE_DEV, "开发", TaskPhaseStatus.PENDING, null, null),
                new TaskPhase(PHASE_ACCEPT, "验收", TaskPhaseStatus.PENDING, null, null));
    }

    private static int currentPhaseIndex(List<TaskPhase> phases) {
        for (int i = 0; i < phases.size(); i++) {
            if (phases.get(i).status() != TaskPhaseStatus.DONE) {
                return i;
            }
        }
        return phases.size();
    }

    /** Progress while phase {@code i} runs: the previous phase's done-boundary (10% for the first). */
    private static int runningProgress(int i) {
        return i == 0 ? 10 : PHASE_DONE_PROGRESS[i - 1];
    }

    private List<TaskPhase> loadPhases(String taskId) {
        Optional<String> json = phaseStore.findPhasesJson(taskId);
        if (json.isEmpty()) {
            return initialCodingPhases();
        }
        try {
            return PHASE_JSON.readValue(json.get(), new TypeReference<List<TaskPhase>>() { });
        } catch (IOException e) {
            log.warn("could not parse phases for task {}: {}", taskId, e.getMessage());
            return initialCodingPhases();
        }
    }

    private void persistPhases(String taskId, List<TaskPhase> phases) {
        try {
            phaseStore.save(taskId, PHASE_JSON.writeValueAsString(phases));
        } catch (JsonProcessingException e) {
            log.warn("could not persist phases for task {}: {}", taskId, e.getMessage());
        }
    }

    private void persistProgress(String taskId, int progress) {
        tasks.findTask(taskId).ifPresent(t -> {
            if (t.status() == TaskStatus.RUNNING) {
                tasks.save(t.withProgress(progress));
            }
        });
    }

    private void addSpend(String taskId, BigDecimal cost) {
        if (cost == null || cost.signum() == 0) {
            return;
        }
        tasks.findTask(taskId).ifPresent(t ->
                tasks.save(t.withSpend((t.spend() == null ? BigDecimal.ZERO : t.spend()).add(cost))));
    }

    private static String firstNonBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private String phasePrompt(String key, String brief) {
        String spec = brief == null ? "" : brief;
        return switch (key) {
            case PHASE_PRD -> """
                    你在执行一个【编码任务】，采用平台强制的三步流程：PRD → 开发 → 验收。当前是【第1步：PRD】。
                    请充分理解下面的任务说明，产出一份 PRD（Markdown），必须包含三个小节：## 背景 / ## 需求 / ## 技术思路。
                    直接把 PRD 的 Markdown 作为你的最终回复输出，不要写入文件，也不要只回复确认语。
                    如遇必须由用户拍板的关键决策，输出一行以 %s: 开头的问题并停止。

                    %s

                    任务说明：
                    %s""".formatted(CONFIRM_MARKER, codingFlowTemplate, spec);
            case PHASE_DEV -> """
                    很好，PRD 已确认。现在是【第2步：开发】。
                    请依据上面的 PRD 实现所需改动（编辑/新增代码），完成后简述你改了哪些文件、做了什么。
                    如遇关键决策，输出一行以 %s: 开头的问题并停止。""".formatted(CONFIRM_MARKER);
            case PHASE_ACCEPT -> """
                    现在是【第3步：验收】。
                    请对改动进行验收：运行相关测试 / 构建 / 必要的 curl 验证。
                    前端任务尽量用 Playwright 截图；若环境无无头浏览器，请如实说明并降级为“构建通过 + 关键 DOM 断言”，不要编造截图。
                    必须给出验收证据（命令输出、测试结果、断言等）。如缺少关键依赖需用户决策，输出一行以 %s: 开头的问题并停止。"""
                    .formatted(CONFIRM_MARKER);
            default -> spec;
        };
    }

    private static String loadCodingFlowTemplate() {
        try (InputStream in = TaskService.class.getResourceAsStream("/flows/coding.md")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Settles to a terminal status, or pauses at NEEDS_CONFIRMATION; then frees the
     * session (terminal) or holds it for the paused task (confirmation) and dispatches
     * any queued work now that a slot is free.
     */
    private void settle(Task task, TaskStatus status, int progress, String error, BigDecimal turnCost) {
        Task latest = tasks.findTask(task.id()).orElse(task);
        Task next = status == TaskStatus.NEEDS_CONFIRMATION
                ? latest.withStatus(TaskStatus.NEEDS_CONFIRMATION).withProgress(progress)
                : latest.finished(status, progress, error);
        BigDecimal spend = (latest.spend() == null ? BigDecimal.ZERO : latest.spend())
                .add(turnCost == null ? BigDecimal.ZERO : turnCost);
        tasks.save(next.withSpend(spend));
        log.info("task {} -> {} ({}%), spend={}", task.id(), status, next.progress(), spend);

        synchronized (schedulerLock) {
            TaskSessionPool.Session session = pool.sessionForTask(task.id());
            if (session != null) {
                if (status == TaskStatus.NEEDS_CONFIRMATION) {
                    pool.pause(session);
                } else {
                    pool.release(session);
                }
            }
        }
        tryDispatch(task.createdBy());
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

    private TaskDto toDto(Task t) {
        return toDto(t, t.workspaceDir() == null ? null : boundSessionId(t));
    }

    /** List view: session id from the workspace name only, so listing N tasks doesn't cost N run-state reads. */
    private TaskDto toListDto(Task t) {
        return toDto(t, t.workspaceDir() == null ? null : deriveSessionId(t));
    }

    private TaskDto toDto(Task t, String sessionId) {
        return new TaskDto(t.id(), t.title(), t.source().name().toLowerCase(),
                t.repo(), t.branch(), t.flow(), t.status().name().toLowerCase(),
                t.progress(), t.spend(), t.model(), t.createdBy(), t.error(),
                t.createdAt(), t.startedAt(), t.finishedAt(), t.updatedAt(),
                t.agentType().name().toLowerCase(), sessionId, t.workspaceDir(), sessionState(t.id(), sessionId));
    }

    private String sessionState(String taskId, String sessionId) {
        if (sessionId == null) {
            return null;
        }
        synchronized (schedulerLock) {
            TaskSessionPool.Session s = pool.findSession(sessionId);
            if (s == null) {
                return "released";
            }
            if (!taskId.equals(s.taskId)) {
                return s.state == TaskSessionPool.State.IDLE ? "idle" : "in_use";
            }
            return s.state == TaskSessionPool.State.RESERVED ? "reserved" : "busy";
        }
    }

    private MessageDto toMessageDto(String taskId, ChatMessage m) {
        List<String> urls = m.role() == MessageRole.USER
                ? images.urls(ApiPaths.TASKS + "/" + taskId + "/messages", taskId, m.id()) : List.of();
        return new MessageDto(m.id(), m.role().name().toLowerCase(), m.content(), m.createdAt(), urls);
    }

    private static TaskEventDto toEventDto(AgentEvent e) {
        return new TaskEventDto(e.type().name().toLowerCase(), e.seq(), e.payload(), e.createdAt());
    }

    private static TaskPhaseDto toPhaseDto(TaskPhase p) {
        return new TaskPhaseDto(p.key(), p.name(), p.status().name().toLowerCase(), p.content(), p.evidence());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
