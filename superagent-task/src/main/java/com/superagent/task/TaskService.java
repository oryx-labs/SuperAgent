package com.superagent.task;

import com.superagent.api.ApiPaths;
import com.superagent.api.chat.ImageUploadDto;
import com.superagent.api.chat.MessageDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDetailDto;
import com.superagent.api.task.TaskDto;
import com.superagent.api.task.TaskEventDto;
import com.superagent.api.task.TaskPageDto;
import com.superagent.api.task.TaskStatsDto;
import com.superagent.api.task.TaskPhaseDto;
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
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.Task;
import com.superagent.platform.provider.CredentialService;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.core.task.TaskKind;
import com.superagent.core.task.TaskPhase;
import com.superagent.core.task.TaskPhaseStatus;
import com.superagent.core.task.TaskPhaseStore;
import com.superagent.core.task.TaskRunState;
import com.superagent.core.task.TaskRunStateStore;
import com.superagent.core.task.TaskSource;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import jakarta.annotation.PreDestroy;
import com.superagent.files.FileService;
import com.superagent.skills.SkillService;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.runtime.mcp.McpGrants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    /** Each task's latest final reply, so the "finished" to-do item can say what came out of it. */
    private final Map<String, String> lastReply = new ConcurrentHashMap<>();
    private final ImageAttachmentStore images;
    private final FileService files;
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
    private final McpGrants mcpGrants;   // null = task runs get no SuperAgent tools (files, skills)
    private final SkillService skills;   // null = tasks run without skills (tests)
    private final TaskAgentStore taskAgents;   // null = task→agent links not recorded (tests)
    private CredentialService credentials;     // null in tests: runs use the server's environment


    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCredentials(CredentialService credentials) {
        this.credentials = credentials;
    }

    private com.superagent.platform.connector.ConnectorService connectors; // null in tests: no company connectors

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConnectors(com.superagent.platform.connector.ConnectorService connectors) {
        this.connectors = connectors;
    }
    private com.superagent.core.task.TaskDoers doers; // null in tests: a task runs as no one in particular
    private com.superagent.core.usage.UsageRecorder usage; // null in tests: tokens aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageRecorder(com.superagent.core.usage.UsageRecorder usage) {
        this.usage = usage;
    }
    private com.superagent.core.task.TaskModelSelector selector; // null in tests: tasks run on the model they were given
    private com.superagent.core.task.TaskModelChoiceStore choices; // null in tests: decisions aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setModelSelection(com.superagent.core.task.TaskModelSelector selector,
                           com.superagent.core.task.TaskModelChoiceStore choices) {
        this.selector = selector;
        this.choices = choices;
    }

    /**
     * Wakes the scheduler, which admits new tasks in the background (choosing their model first). Null until a
     * {@link TaskScheduler} takes over (tests): then a new task is admitted at once, as before.
     */
    private volatile Runnable wakeScheduler;
    /** Tasks handed to admission (being judged, or queued for a session), so a sweep doesn't take them twice. */
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDoers(com.superagent.core.task.TaskDoers doers) {
        this.doers = doers;
    }
    private com.superagent.platform.provider.ProviderService providers; // null in tests: no model fallback

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProviders(com.superagent.platform.provider.ProviderService providers) {
        this.providers = providers;
    }
    private final ApplicationEventPublisher events;   // null = no to-do items (tests)
    private final CodingFlow codingFlow;

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
            SkillService skills,
            TaskAgentStore taskAgents,
            ApplicationEventPublisher events,
            FileService files) {
        this.tasks = tasks;
        this.phaseStore = phaseStore;
        this.codingFlow = new CodingFlow(phaseStore);
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
        this.taskAgents = taskAgents;
        this.events = events;
        this.files = files;
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
        // New tasks still waiting for their first session: the scheduler takes them up once it starts.
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

    // Every entry point takes the user it acts for: a task is visible only to the user it belongs to
    // (whoever created it — directly, or through their agents, SuperAgent or automations). Someone else's
    // task reads as "not found", so ids can't be probed.

    /** The user's live tasks, newest first. */
    public List<TaskDto> list(String owner) {
        return owned(owner).map(this::toListDto).toList();
    }

    /** The tasks this agent set off (via its chat MCP tools), newest first, each with the reply that did; excludes deleted. */
    public List<TaskDto> listForAgent(String agentId) {
        if (taskAgents == null) {
            return List.of();
        }
        List<TaskDto> out = new ArrayList<>();
        for (com.superagent.core.task.TaskOrigin o : taskAgents.originsForAgent(agentId)) {
            tasks.findTask(o.taskId()).filter(t -> t.status() != TaskStatus.DELETED).ifPresent(t ->
                    out.add(toDto(t, t.workspaceDir() == null ? null : deriveSessionId(t), o.messageId())));
        }
        return out;
    }

    private java.util.stream.Stream<Task> owned(String owner) {
        return tasks.listTasks().stream()
                .filter(t -> t.status() != TaskStatus.DELETED && t.createdBy() != null && t.createdBy().equals(owner))
                .sorted(Comparator.comparing(Task::createdAt).reversed());
    }

    /**
     * One page of the user's list, newest first; a page past the end is clamped to the last one.
     * {@code status}: null/all, "active" (pending or running), or one task status (e.g. needs_confirmation).
     */
    public TaskPageDto page(String owner, String status, int page, int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        List<Task> live = owned(owner).filter(t -> matches(t, status)).toList();
        int pages = Math.max(1, (live.size() + pageSize - 1) / pageSize);
        int current = Math.min(Math.max(1, page), pages);
        List<TaskDto> items = live.stream().skip((long) (current - 1) * pageSize).limit(pageSize)
                .map(this::toListDto).toList();
        return new TaskPageDto(items, live.size(), current, pageSize);
    }

    private static boolean matches(Task t, String status) {
        if (status == null || status.isBlank() || "all".equals(status)) {
            return true;
        }
        if ("active".equals(status)) {
            return t.status() == TaskStatus.PENDING || t.status() == TaskStatus.RUNNING;
        }
        return t.status().name().equalsIgnoreCase(status);
    }

    /** The user's overview: counts by state, today's completions (server time zone) and this month's spend. */
    public TaskStatsDto stats(String owner) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Instant today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant month = java.time.LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        List<Task> all = owned(owner).toList();
        int active = 0, waiting = 0, doneToday = 0, failed = 0;
        BigDecimal spend = BigDecimal.ZERO;
        for (Task t : all) {
            switch (t.status()) {
                case PENDING, RUNNING -> active++;
                case NEEDS_CONFIRMATION -> waiting++;
                case DONE -> doneToday += t.finishedAt() != null && !t.finishedAt().isBefore(today) ? 1 : 0;
                case ERROR -> failed++;
                default -> { }
            }
            if (t.spend() != null && !t.createdAt().isBefore(month)) {
                spend = spend.add(t.spend());
            }
        }
        return new TaskStatsDto(all.size(), active, waiting, doneToday, failed, spend);
    }

    public TaskDetailDto detail(String id, String owner) {
        Task task = require(id, owner);
        List<MessageDto> messages = runStore.listMessages(id).stream().map(m -> toMessageDto(id, m)).toList();
        List<TaskEventDto> events = runStore.listEvents(id).stream().map(TaskService::toEventDto).toList();
        List<TaskPhaseDto> phases = TaskKind.fromStorage(task.flow()) == TaskKind.CODING
                ? codingFlow.load(id).stream().map(TaskService::toPhaseDto).toList()
                : List.of();
        return new TaskDetailDto(toDto(task), task.brief(), messages, events, phases);
    }

    /** Events from {@code offset} onward (chronological), for incremental polling of a running task. */
    public List<TaskEventDto> eventsFrom(String id, String owner, int offset) {
        require(id, owner);
        List<AgentEvent> all = runStore.listEvents(id);
        int from = Math.max(0, offset);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, all.size()).stream().map(TaskService::toEventDto).toList();
    }

    /** Lightweight status/progress snapshot for polling, without the event stream. */
    public TaskDto status(String id, String owner) {
        return toDto(require(id, owner));
    }

    // --- lifecycle ---

    public TaskDto create(CreateTaskRequest request) {
        return create(request, null);
    }

    public TaskDto create(CreateTaskRequest request, String owner) {
        return create(request, owner, null);
    }

    /**
     * {@code owner} is the requesting user (null = the default owner); the files it makes are theirs.
     * {@code agentId} is the agent creating it, whose skills the task then uses (null = all skills).
     */
    public TaskDto create(CreateTaskRequest request, String owner, String agentId) {
        return create(request, owner, agentId, null, null);
    }

    /**
     * As {@link #create(CreateTaskRequest, String, String)}, set off from a chat reply ({@code sessionId}/{@code messageId}),
     * whose conversation then hears how it went. The task runs as that agent (its character and memory), on a model
     * chosen for the job.
     */
    public TaskDto create(CreateTaskRequest request, String owner, String agentId, String sessionId, String messageId) {
        String id = UUID.randomUUID().toString();
        AgentType agentType = AgentType.fromString(request.agentType());
        String model = blankToNull(request.model());
        // A stand-in until just before it runs: then its model is chosen for the job (TaskModelSelector).
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
        Task task = new Task(id, cleanTitle(request.title()), blankToNull(request.brief()),
                source,
                blankToNull(request.repo()), blankToNull(request.branch()), kind.storageName(),
                TaskStatus.PENDING, 0, BigDecimal.ZERO, model, agentType,
                null, owner == null || owner.isBlank() ? defaultOwner : owner, null, now, null, null, now, null);
        tasks.save(task);
        if (kind == TaskKind.CODING) {
            codingFlow.persist(id, CodingFlow.initialPhases());
        }
        if (agentId != null && skills != null) {
            skills.bindTaskToAgent(id, agentId); // before scheduling, so the first turn already has them
        }
        if (agentId != null && taskAgents != null) {
            taskAgents.link(id, agentId, sessionId, messageId); // so the agent's panel and chat can show the work it set off
        }
        if ((selector == null || !selector.enabled()) && choices != null) {
            // Nothing will judge this task (admit skips choosing): say now why it runs on what it runs on.
            choices.save(defaultChoice(task, request, agentId, now));
        }
        log.info("created task {} ({}), kind={}", id, task.title(), kind.storageName());
        publish(task, agentId);
        Runnable wake = wakeScheduler;
        if (wake != null) {
            wake.run(); // recorded now, run by the scheduler
        } else {
            admit(task);
        }
        return toDto(task);
    }

    /**
     * The kind the creator named; else coding only when it names a repository. Never guessed from the wording:
     * "实现", "接口" or "测试" turn up in all sorts of work, and a general task put through the coding flow's three
     * gated steps just stalls.
     */
    static TaskKind resolveKind(CreateTaskRequest request) {
        String k = request.kind();
        if (k != null && !k.isBlank() && !k.equalsIgnoreCase("auto")) {
            return TaskKind.fromStorage(k);
        }
        return request.repo() != null && !request.repo().isBlank() ? TaskKind.CODING : TaskKind.GENERAL;
    }

    // --- admission (driven by TaskScheduler) ---

    /** The scheduler takes over admitting new tasks; {@code wake} prompts it to look now. */
    void useScheduler(Runnable wake) {
        this.wakeScheduler = wake;
    }

    /** New tasks waiting to start, oldest first, that nothing has taken up yet. */
    List<Task> awaitingAdmission() {
        List<Task> waiting = tasks.listTasks().stream()
                .filter(t -> t.status() == TaskStatus.PENDING && t.workspaceDir() == null)
                .sorted(Comparator.comparing(Task::createdAt)).toList();
        Set<String> ids = new java.util.HashSet<>();
        waiting.forEach(t -> ids.add(t.id()));
        admitted.retainAll(ids); // started, stopped or deleted since: forget them
        return waiting.stream().filter(t -> !admitted.contains(t.id())).toList();
    }

    /**
     * Takes a new task up: first its model, when none was decided yet (a model is asked, so on a
     * worker thread), then a session. Where task dependencies will be checked, when there are some.
     */
    void admit(Task task) {
        if (!admitted.add(task.id())) {
            return;
        }
        boolean decided = choices == null || !choices.list(task.id()).isEmpty();
        if (selector == null || !selector.enabled() || decided) {
            schedule(task);
            return;
        }
        executor.execute(() -> {
            try {
                chooseModel(task);
            } catch (RuntimeException e) {
                log.warn("choosing a model for task {} failed; it runs on {}", task.id(), task.model(), e);
            }
            tasks.findTask(task.id()).filter(t -> t.status() == TaskStatus.PENDING && t.workspaceDir() == null)
                    .ifPresentOrElse(this::schedule, () -> admitted.remove(task.id()));
        });
    }

    private void chooseModel(Task task) {
        var choice = selector.choose(task).orElse(null);
        if (choice == null) {
            return; // nothing can run: the stand-in stays, and fails at the runner with a clear error
        }
        AgentType type = AgentType.fromString(choice.cli());
        if (!runners.containsKey(type)) {
            choice = new com.superagent.core.task.TaskModelChoice(task.id(), 0, com.superagent.core.task.TaskModelChoice.BY_FALLBACK,
                    choice.difficulty(), task.tier(), task.model(), task.agentType().cliName(), choice.judge(), choice.candidates(),
                    choice.reasoning() + "（选中的 " + choice.model() + " 要用 " + choice.cli() + "，这里还不能用它执行任务，改用默认的 "
                            + task.model() + "。）", choice.createdAt());
            type = task.agentType();
        }
        recordChoice(choice);
        AgentType runOn = type;
        String model = choice.model();
        String tier = choice.tier();
        tasks.findTask(task.id()).filter(t -> t.status() == TaskStatus.PENDING)
                .ifPresent(t -> tasks.save(t.withModel(model, runOn, tier)));
        log.info("task {} will run on {} ({}), decided by {}", task.id(), model, tier, choice.by());
    }

    /**
     * Why a task runs on what it runs on when tiers are off: nothing is judged, so the reason is where the model came
     * from (the creator, or the configured defaults) and what else could have run it.
     */
    private com.superagent.core.task.TaskModelChoice defaultChoice(Task task, CreateTaskRequest request, String agentId,
                                                                  Instant at) {
        String runs = effectiveModel(task);
        String shown = runs != null ? runs : task.agentType().cliName() + " 自己的默认模型";
        StringBuilder why = new StringBuilder("没有开启模型分档，所以不按任务难度选模型，按默认规则：");
        if (task.model() != null) {
            String who = "automation".equals(request.source()) ? "这个定时任务" : agentId != null ? "发起它的 Agent" : "创建任务时";
            why.append(who).append("指定了 ").append(task.model()).append("，就用它。");
        } else if (runs != null && task.agentType() == AgentType.CLAUDE) {
            why.append("创建时没有指定模型，用部署配置的默认模型 ").append(runs).append("（superagent.agent.default-model）。");
        } else if (runs != null) {
            why.append("创建时没有指定模型，用 ").append(task.agentType().cliName())
                    .append(" 下第一个通过可用性检查的模型 ").append(runs).append("。");
        } else {
            why.append("创建时没有指定模型，系统也没有配置默认模型，由 ").append(task.agentType().cliName())
                    .append(" 用它自己的默认模型。");
        }
        why.append("想让系统按任务难度自动挑模型，可以在系统设置里开启「按档位选择模型」。");
        return new com.superagent.core.task.TaskModelChoice(task.id(), 1, com.superagent.core.task.TaskModelChoice.BY_DEFAULT,
                null, null, shown, task.agentType().cliName(), null, runnableModels(), why.toString(), at);
    }

    /** Every model that passed its check, per CLI: what the task could have run on. */
    private String runnableModels() {
        if (providers == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AgentType type : runners.keySet()) {
            providers.storedModels(type.cliName()).stream().filter(com.superagent.api.provider.ProviderModelDto::canInfer)
                    .forEach(m -> sb.append(m.id()).append(" · ").append(type.cliName()).append("\n"));
        }
        return sb.isEmpty() ? null : sb.toString().stripTrailing();
    }

    private void recordChoice(com.superagent.core.task.TaskModelChoice choice) {
        if (choices != null) {
            choices.save(choice.withSeq(choices.list(choice.taskId()).size() + 1));
        }
    }

    /** The decisions about which model runs this task, oldest first. */
    public List<com.superagent.api.task.TaskModelChoiceDto> modelChoices(String id, String owner) {
        require(id, owner);
        return choices == null ? List.of() : choices.list(id).stream().map(c -> new com.superagent.api.task.TaskModelChoiceDto(
                c.seq(), c.by(), c.difficulty(), c.tier(), c.model(), c.cli(), c.judge(), c.candidates(), c.reasoning(),
                c.createdAt())).toList();
    }

    /**
     * A task that failed on a model chosen for it moves up a tier and starts over, rather than failing: start cheap,
     * pay more only when it's needed. Not when the user picked the tier, nor once there's nothing stronger.
     */
    private boolean escalate(Task task, String error) {
        if (selector == null || choices == null || !selector.enabled()) {
            return false;
        }
        List<com.superagent.core.task.TaskModelChoice> made = choices.list(task.id());
        if (made.isEmpty() || made.stream().anyMatch(c -> com.superagent.core.task.TaskModelChoice.BY_USER.equals(c.by()))) {
            return false;
        }
        var up = selector.escalate(task, error == null ? "执行失败" : error).orElse(null);
        if (up == null || !runners.containsKey(AgentType.fromString(up.cli()))) {
            return false;
        }
        recordChoice(up);
        synchronized (schedulerLock) {
            TaskSessionPool.Session session = pool.sessionForTask(task.id());
            if (session != null) {
                pool.release(session);
            }
        }
        runStates.delete(task.id());
        if (TaskKind.fromStorage(task.flow()) == TaskKind.CODING) {
            codingFlow.persist(task.id(), CodingFlow.initialPhases());
        }
        runStore.appendEvent(AgentEvent.of(task.id(), Long.MAX_VALUE, AgentEventType.SYSTEM,
                "在 " + task.model() + " 上失败，改用更贵的 " + up.model() + " 从头重新执行。"));
        Task again = task.requeuedOn(up.model(), AgentType.fromString(up.cli()), up.tier());
        tasks.save(again);
        publish(again, null);
        admitted.remove(task.id());
        log.info("task {} failed on {}: moving up to {}", task.id(), task.model(), up.model());
        Runnable wake = wakeScheduler;
        if (wake != null) {
            wake.run();
        } else {
            admit(again);
        }
        tryDispatch(task.createdBy());
        return true;
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
    public void sendMessage(String id, String owner, String content) {
        sendMessage(id, owner, content, List.of(), List.of());
    }

    /** {@code fileIds}: files the user uploaded for this message (see FileService#upload); they go into the workspace. */
    public void sendMessage(String id, String owner, String text, List<ImageUploadDto> uploads, List<String> fileIds) {
        Task task = require(id, owner);
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
            List<ImageAttachment> attached = new ArrayList<>(images.save(id, messageId, uploads));
            FileService.Delivered delivered = fileIds == null || fileIds.isEmpty() || files == null ? null
                    : FileService.deliver(files.attach(fileIds, owner, id, messageId), task.workspaceDir());
            if (delivered != null) {
                attached.addAll(delivered.images());
            }
            runStore.appendMessage(new ChatMessage(messageId, id,
                    MessageRole.USER, text == null ? "" : text, Instant.now()));
            if (files != null) {
                String agentId = taskAgents == null ? null : taskAgents.agentOf(id).orElse(null);
                files.saveLinks(owner, agentId, id, id, messageId, text); // links are resources too
            }
            if (!attached.isEmpty()) {
                nextTurnImages.put(id, attached);
            }
            String content = withNote(agentPrompt(text, attached), delivered);
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

    private static String withNote(String prompt, FileService.Delivered delivered) {
        return delivered == null ? prompt : prompt + "\n\n" + delivered.note();
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
        publish(running, null);
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
        if (task.agentType() == AgentType.CLAUDE) {
            return defaultModel;
        }
        // Kimi and Qwen need a model named; any CLI does better with one the probe saw answer than none.
        return providers == null || task.agentType() == AgentType.CODEX ? null
                : providers.storedModels(task.agentType().cliName()).stream()
                        .filter(com.superagent.api.provider.ProviderModelDto::canInfer)
                        .map(com.superagent.api.provider.ProviderModelDto::id).findFirst().orElse(null);
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

    public void stop(String id, String owner) {
        Task waiting = require(id, owner);
        if (waiting.status() == TaskStatus.PENDING && waiting.workspaceDir() == null) {
            synchronized (schedulerLock) { // not started yet: take it out of the queue
                Deque<QueuedRun> q = queues.get(waiting.createdBy());
                if (q != null) {
                    q.removeIf(r -> r.taskId().equals(id));
                }
            }
            Task stopped = waiting.finished(TaskStatus.STOPPED, 0, null);
            tasks.save(stopped);
            publish(stopped, null);
            return;
        }
        Cancellation cancellation = activeRuns.get(id);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    /** Soft delete: marks the task DELETED so it drops out of the list; the row is kept. */
    public void delete(String id, String owner) {
        Task task = require(id, owner);
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
                Task failed = task.finished(TaskStatus.ERROR, task.progress(),
                        "retry budget exhausted after " + MAX_ATTEMPTS + " attempts");
                tasks.save(failed);
                publish(failed, null);
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
        publish(running, null);
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
            if (coding && !wrapBriefPrompt && CodingFlow.currentIndex(codingFlow.load(task.id())) >= codingFlow.load(task.id()).size()) {
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

        // The task's spend: our estimate from its tokens, for every CLI; what the CLI says only where we have no price.
        AtomicReference<BigDecimal> estimated = new AtomicReference<>();
        com.superagent.core.usage.UsageContext paying = new com.superagent.core.usage.UsageContext(task.createdBy(),
                com.superagent.core.usage.UsageContext.SOURCE_TASK,
                taskAgents == null ? null : taskAgents.agentOf(task.id()).orElse(null), task.id(), null, task.agentType().cliName());
        AgentEventSink sink = event -> {
            if (event.type() == AgentEventType.TOKENS) {
                Double usd = usage == null ? null : usage.record(paying, event.payload());
                if (usd != null) {
                    estimated.accumulateAndGet(BigDecimal.valueOf(usd), (a, b) -> a == null ? b : a.add(b));
                }
                return;
            }
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
        // A per-turn token gives this run the file and skill tools for its owner only, revoked after the turn.
        McpGrants.Grant grant = mcpGrants == null || task.workspaceDir() == null ? null
                : mcpGrants.openTask(task.createdBy(), task.id(), task.workspaceDir());
        // The company's connectors, with the identity of whoever created the task (their own authorizations).
        List<McpServerSpec> connected = connectors == null ? List.of() : connectors.mcpServersFor(task.createdBy());
        List<String> tools = allowedTools;
        if (grant != null || !connected.isEmpty()) {
            tools = new ArrayList<>(allowedTools);
            if (grant != null) {
                tools.addAll(McpGrants.WORK_TOOLS);
            }
            tools.addAll(com.superagent.platform.connector.ConnectorService.toolPatterns(connected));
        }
        String cli = task.agentType().cliName();
        CredentialService.Choice choice = credentials == null ? new CredentialService.Choice(null) : credentials.current(cli);
        AgentRunRequest request = new AgentRunRequest(
                agentSessionId, sessionFirstTurn ? null : agentSessionId, task.workspaceDir(),
                wrapBriefPrompt ? wrapBrief(prompt) : prompt,
                effectiveModel(task),
                tools, skipPermissions, McpGrants.withServers(grant == null ? null : mcpGrants.mcpConfig(grant), connected),
                taskSystemPrompt(task, grant != null),
                nextTurnImages.remove(task.id()), null, choice.credential());
        try {
            runner.run(request, sink, cancellation);
            // The endpoint or key failed before the agent produced anything: switch keys and run the turn again.
            boolean nothingSaid = text.isEmpty() && result.get() == null;
            if (credentials != null && nothingSaid && error.get() != null && !cancellation.isCancelled()) {
                java.util.Optional<CredentialService.Choice> next = credentials.failover(cli, choice, error.get());
                if (next.isPresent()) {
                    log.info("task {}: retrying the turn on endpoint {}", task.id(), next.get().id());
                    error.set(null);
                    runner.run(request.withCredential(next.get().credential()), sink, cancellation);
                }
            }
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
        if (finalText != null && !finalText.isBlank()) {
            lastReply.put(task.id(), finalText);
        }
        return new TurnResult(finalText, estimated.get() != null ? estimated.get() : turnCost.get(), cancellation.isCancelled());
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
        List<TaskPhase> phases = new ArrayList<>(codingFlow.load(task.id()));
        int start = CodingFlow.currentIndex(phases);
        boolean firstTurn = sessionFirstTurn;
        try {
            for (int i = start; i < phases.size(); i++) {
                TaskPhase phase = phases.get(i).withStatus(TaskPhaseStatus.RUNNING);
                phases.set(i, phase);
                codingFlow.persist(task.id(), phases);
                persistProgress(task.id(), CodingFlow.runningProgress(i));

                String prompt = (i == start && resumePrompt != null)
                        ? resumePrompt : codingFlow.prompt(phase.key(), task.brief());
                TurnResult turn = executeTurn(task, prompt, agentSessionId, firstTurn, false, null, cancellation);
                firstTurn = false;
                addSpend(task.id(), turn.cost());

                if (turn.cancelled()) {
                    settle(task, TaskStatus.STOPPED, CodingFlow.runningProgress(i), null, BigDecimal.ZERO);
                    return;
                }
                if (turn.needsConfirmation()) {
                    settle(task, TaskStatus.NEEDS_CONFIRMATION, CodingFlow.runningProgress(i), null, BigDecimal.ZERO);
                    return; // phase stays RUNNING; a user reply resumes it here
                }

                if (CodingFlow.PRD.equals(phase.key())) {
                    String prd = firstNonBlank(turn.finalText());
                    if (prd == null) {
                        TurnResult retry = executeTurn(task, CodingFlow.PRD_RETRY_PROMPT, agentSessionId, false, false, null, cancellation);
                        addSpend(task.id(), retry.cost());
                        if (retry.cancelled()) {
                            settle(task, TaskStatus.STOPPED, CodingFlow.runningProgress(i), null, BigDecimal.ZERO);
                            return;
                        }
                        prd = firstNonBlank(retry.finalText());
                        if (prd == null) {
                            pauseForGate(task, phases, i, "第1步未产出 PRD");
                            return;
                        }
                    }
                    phase = phase.withContent(prd);
                } else if (CodingFlow.ACCEPT.equals(phase.key())) {
                    String evidence = firstNonBlank(turn.finalText());
                    if (evidence == null) {
                        pauseForGate(task, phases, i, "缺少验收证据");
                        return;
                    }
                    phase = phase.withEvidence(evidence);
                }

                phase = phase.withStatus(TaskPhaseStatus.DONE);
                phases.set(i, phase);
                codingFlow.persist(task.id(), phases);
                persistProgress(task.id(), CodingFlow.doneProgress(i));
            }
        } catch (Exception e) {
            codingFlow.persist(task.id(), phases);
            failTurn(task, task.progress(), e);
            return;
        }
        writeOutput(task, phases.get(phases.size() - 1).evidence());
        settle(task, TaskStatus.DONE, 100, null, BigDecimal.ZERO);
    }

    /** Pauses a coding task at NEEDS_CONFIRMATION, surfacing a gate reason into the visible stream. */
    private void pauseForGate(Task task, List<TaskPhase> phases, int phaseIndex, String reason) {
        codingFlow.persist(task.id(), phases); // current phase stays RUNNING, so a reply resumes it
        String text = CONFIRM_MARKER + ": " + reason;
        runStore.appendEvent(AgentEvent.of(task.id(), Long.MAX_VALUE, AgentEventType.TEXT, text));
        runStore.appendMessage(new ChatMessage(UUID.randomUUID().toString(), task.id(),
                MessageRole.ASSISTANT, text, Instant.now()));
        settle(task, TaskStatus.NEEDS_CONFIRMATION, CodingFlow.runningProgress(phaseIndex), null, BigDecimal.ZERO);
    }

    private void failTurn(Task task, int progress, Exception e) {
        log.error("task {} failed", task.id(), e);
        String detail = e.getMessage() != null ? e.getMessage() : "task run failed";
        runStore.appendEvent(AgentEvent.of(task.id(), Long.MAX_VALUE, AgentEventType.ERROR, detail));
        settle(task, TaskStatus.ERROR, progress, detail, BigDecimal.ZERO);
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

    /**
     * Settles to a terminal status, or pauses at NEEDS_CONFIRMATION; then frees the
     * session (terminal) or holds it for the paused task (confirmation) and dispatches
     * any queued work now that a slot is free.
     */
    private void settle(Task task, TaskStatus status, int progress, String error, BigDecimal turnCost) {
        Task latest = tasks.findTask(task.id()).orElse(task);
        if (status == TaskStatus.ERROR) {
            BigDecimal spent = (latest.spend() == null ? BigDecimal.ZERO : latest.spend())
                    .add(turnCost == null ? BigDecimal.ZERO : turnCost);
            if (escalate(latest.withSpend(spent), error)) {
                return;
            }
        }
        Task next = status == TaskStatus.NEEDS_CONFIRMATION
                ? latest.withStatus(TaskStatus.NEEDS_CONFIRMATION).withProgress(progress)
                : latest.finished(status, progress, error);
        BigDecimal spend = (latest.spend() == null ? BigDecimal.ZERO : latest.spend())
                .add(turnCost == null ? BigDecimal.ZERO : turnCost);
        tasks.save(next.withSpend(spend));
        log.info("task {} -> {} ({}%), spend={}", task.id(), status, next.progress(), spend);
        publish(next, null);

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

    /** Tells the to-do page (and anyone else listening) the task's status; {@code agentId} only at creation. */
    private void publish(Task t, String agentId) {
        if (events != null) {
            boolean settled = t.status() != TaskStatus.PENDING && t.status() != TaskStatus.RUNNING;
            String result = t.status() == TaskStatus.DONE ? lastReply.get(t.id()) : null;
            if (settled) {
                lastReply.remove(t.id());
            }
            events.publishEvent(new NoticeEvents.TaskStatusChanged(t.id(), t.createdBy(), t.title(),
                    t.source().name().toLowerCase(Locale.ROOT), t.status().name().toLowerCase(Locale.ROOT), t.error(),
                    agentId, result));
        }
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

    /** The task's skills index, plus how to hand over files when the run has the file tools. */
    private String taskSystemPrompt(Task task, boolean withTools) {
        String index = skills == null ? null : skills.install(task.workspaceDir(), skills.forTask(task.id(), task.createdBy()), withTools);
        String prompt = join(doerPrompt(task), index);
        return withTools ? join(prompt, McpGrants.FILES_HINT) : prompt;
    }

    /**
     * Who does this task: the agent that set it off, with its character and memory. Every turn, not only the first:
     * a pool session runs one task after another, and the next may be another agent's.
     */
    private String doerPrompt(Task task) {
        if (doers == null || taskAgents == null) {
            return null;
        }
        return taskAgents.agentOf(task.id()).flatMap(doers::doer).map(d -> {
            StringBuilder sb = new StringBuilder("## 你是谁\n你是「").append(d.name())
                    .append("」，这个任务由你自己执行。");
            if (d.character() != null && !d.character().isBlank()) {
                sb.append("\n\n").append(d.character().strip());
            }
            if (d.memory() != null && !d.memory().isBlank()) {
                sb.append("\n\n## 你的记忆（之前和用户共事时记下的）\n").append(d.memory().strip());
            }
            return sb.toString();
        }).orElse(null);
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        return b == null || b.isBlank() ? a : a + "\n\n" + b;
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
                你在执行一个任务。请先理解下面的任务说明，然后直接完成它；交付前自己检查一遍是否满足要求。
                如果说明存在歧义，或有需要用户拍板才能继续的关键决策，不要擅自假设——请输出一行以 \
                """ + CONFIRM_MARKER + """
: 开头、后接你的具体问题，然后停止等待用户答复。用户会看到一个「确认继续」按钮，所以问题要写成能直接点确认的样子：\
                写清你打算怎么做；有几个选项时列出来，并标出你推荐的那个（确认就按推荐的做）。否则请直接完成，并在结尾给出交付总结。

                任务说明：
                """ + (brief == null ? "" : brief);
    }

    /** The live task if it belongs to {@code owner}; anything else — missing or someone else's — is "not found". */
    private Task require(String id, String owner) {
        return tasks.findTask(id)
                .filter(t -> t.status() != TaskStatus.DELETED && t.createdBy() != null && t.createdBy().equals(owner))
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
        return toDto(t, sessionId, null);
    }

    private TaskDto toDto(Task t, String sessionId, String originMessageId) {
        return new TaskDto(t.id(), t.title(), t.source().name().toLowerCase(),
                t.repo(), t.branch(), t.flow(), t.status().name().toLowerCase(),
                t.progress(), t.spend(), t.model(), t.createdBy(), t.error(),
                t.createdAt(), t.startedAt(), t.finishedAt(), t.updatedAt(),
                t.agentType().name().toLowerCase(), sessionId, t.workspaceDir(), sessionState(t.id(), sessionId), t.tier(), originMessageId);
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
