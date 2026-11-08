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
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.TenantContext;
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
import org.springframework.context.event.EventListener;
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
    private com.superagent.core.timing.TurnTimingStore timings; // null in tests: timings aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTimings(com.superagent.core.timing.TurnTimingStore timings) {
        this.timings = timings;
    }

    private com.superagent.core.project.ProjectAccess projects; // null in tests: no projects

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjects(com.superagent.core.project.ProjectAccess projects) {
        this.projects = projects;
    }

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
    // task id -> its company: each company's sweep only forgets its own (the others' tasks aren't in its list).
    private final Map<String, String> admitted = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDoers(com.superagent.core.task.TaskDoers doers) {
        this.doers = doers;
    }
    private com.superagent.platform.provider.ProviderService providers; // null in tests: no model fallback

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProviders(com.superagent.platform.provider.ProviderService providers) {
        this.providers = providers;
    }
    private com.superagent.core.task.TaskLinkStore links; // null in tests: no subtasks or follow-ups
    private com.superagent.core.task.TaskTagStore tagStore; // null in tests: no tags
    private com.superagent.platform.settings.SettingsService settings; // null in tests: the default limits

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLinks(com.superagent.core.task.TaskLinkStore links) {
        this.links = links;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTagStore(com.superagent.core.task.TaskTagStore tagStore) {
        this.tagStore = tagStore;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(com.superagent.platform.settings.SettingsService settings) {
        this.settings = settings;
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
    }

    /**
     * The pool lives in memory, so without this every restart would start empty and the
     * next task would mint a new session even though finished tasks left reusable ones on
     * disk. Sessions of tasks waiting on a confirmation stay reserved for them; sessions of
     * RUNNING tasks are left to the watchdog, which resumes those tasks on them.
     */
    @EventListener(CompanyStarted.class)
    void restoreSessions() {
        List<Task> all = tasks.listTasks().stream().filter(t -> t.workspaceDir() != null).toList();
        Set<String> running = new java.util.HashSet<>();
        all.stream().filter(t -> t.status() == TaskStatus.RUNNING).forEach(t -> running.add(boundSessionId(t)));
        int restored = 0;
        synchronized (schedulerLock) {
            for (Task t : all) {
                String sessionId = boundSessionId(t);
                if (t.status() != TaskStatus.RUNNING && !holdsSession(t.status())
                        && !running.contains(sessionId)) {
                    pool.restore(t.createdBy(), t.agentType(), sessionId, t.workspaceDir(), null);
                    restored++;
                }
            }
            for (Task t : all) {
                if (holdsSession(t.status())) {
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

    /** The user's live tasks, newest first (a complex task's subtasks are listed under it, not here). */
    public List<TaskDto> list(String owner) {
        Views v = views();
        return owned(owner).filter(t -> !v.isChild(t.id())).map(t -> toListDto(t, v)).toList();
    }

    /** The tasks this agent set off (via its chat MCP tools), newest first, each with the reply that did; excludes deleted. */
    public List<TaskDto> listForAgent(String agentId) {
        if (taskAgents == null) {
            return List.of();
        }
        List<TaskDto> out = new ArrayList<>();
        for (com.superagent.core.task.TaskOrigin o : taskAgents.originsForAgent(agentId)) {
            tasks.findTask(o.taskId()).filter(t -> t.status() != TaskStatus.DELETED).ifPresent(t ->
                    out.add(toDto(t, t.workspaceDir() == null ? null : deriveSessionId(t), o.messageId(), views())));
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
        return page(owner, status, null, null, null, page, size);
    }

    public TaskPageDto page(String owner, String status, String tag, String agentId, String source, int page, int size) {
        return page(owner, status, tag, agentId, source, null, page, size);
    }

    /**
     * As {@link #page(String, String, int, int)}, narrowed to a topic {@code tag}, the agent the tasks ran as and where
     * they came from (each null = any). Subtasks aren't listed; their cost counts towards their complex task.
     */
    public TaskPageDto page(String owner, String status, String tag, String agentId, String source, String projectId,
                            int page, int size) {
        int pageSize = Math.max(1, Math.min(size, 100));
        Views v = views();
        // A project shows every task made in it, whoever started it; otherwise the user's own.
        java.util.stream.Stream<Task> base = projectId == null || projectId.isBlank() || projects == null ? owned(owner)
                : projects.itemsInProject(projectId, com.superagent.core.project.ProjectItemKind.TASK, owner).stream()
                .map(tasks::findTask).flatMap(java.util.Optional::stream).filter(t -> t.status() != TaskStatus.DELETED)
                .sorted(Comparator.comparing(Task::createdAt).reversed());
        List<Task> live = base.filter(t -> !v.isChild(t.id()) && matches(t, status))
                .filter(t -> tag == null || tag.isBlank() || v.tags(t.id()).contains(tag))
                .filter(t -> agentId == null || agentId.isBlank() || agentId.equals(v.agentOf(t.id())))
                .filter(t -> source == null || source.isBlank() || t.source().name().equalsIgnoreCase(source))
                .toList();
        int pages = Math.max(1, (live.size() + pageSize - 1) / pageSize);
        int current = Math.min(Math.max(1, page), pages);
        List<TaskDto> items = live.stream().skip((long) (current - 1) * pageSize).limit(pageSize)
                .map(t -> toListDto(t, v)).toList();
        BigDecimal spend = live.stream().map(v::totalSpend).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TaskPageDto(items, live.size(), current, pageSize, spend);
    }

    /** What the user's tasks can be filtered by, with how many tasks each holds and what they cost. */
    public com.superagent.api.task.TaskFacetsDto facets(String owner) {
        Views v = views();
        List<Task> mine = owned(owner).filter(t -> !v.isChild(t.id())).toList();
        Map<String, Facet> tags = new java.util.TreeMap<>();
        Map<String, Facet> agents = new java.util.LinkedHashMap<>();
        Map<String, Facet> sources = new java.util.LinkedHashMap<>();
        for (Task t : mine) {
            BigDecimal spend = v.totalSpend(t);
            v.tags(t.id()).forEach(tag -> tags.computeIfAbsent(tag, k -> new Facet(k, k)).add(spend));
            String agent = v.agentOf(t.id());
            if (agent != null) {
                agents.computeIfAbsent(agent, k -> new Facet(k, v.agentName(k))).add(spend);
            }
            String src = t.source().name().toLowerCase(Locale.ROOT);
            sources.computeIfAbsent(src, k -> new Facet(k, k)).add(spend);
        }
        java.util.function.Function<Map<String, Facet>, List<com.superagent.api.task.TaskFacetsDto.Facet>> out = m ->
                m.values().stream().sorted(Comparator.comparingInt((Facet f) -> -f.count))
                        .map(f -> new com.superagent.api.task.TaskFacetsDto.Facet(f.key, f.label, f.count, f.spend)).toList();
        return new com.superagent.api.task.TaskFacetsDto(out.apply(tags), out.apply(agents), out.apply(sources));
    }

    private static final class Facet {
        final String key;
        final String label;
        int count;
        BigDecimal spend = BigDecimal.ZERO;

        Facet(String key, String label) {
            this.key = key;
            this.label = label;
        }

        Facet add(BigDecimal s) {
            count++;
            spend = spend.add(s);
            return this;
        }
    }

    private static boolean matches(Task t, String status) {
        if (status == null || status.isBlank() || "all".equals(status)) {
            return true;
        }
        if ("active".equals(status)) {
            return t.status() == TaskStatus.PENDING || t.status() == TaskStatus.RUNNING || t.status() == TaskStatus.WAITING;
        }
        return t.status().name().equalsIgnoreCase(status);
    }

    /** The user's overview: counts by state, today's completions (server time zone) and this month's spend. */
    public TaskStatsDto stats(String owner) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        Instant today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant month = java.time.LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        List<Task> everything = owned(owner).toList();
        Views v = views();
        List<Task> all = everything.stream().filter(t -> !v.isChild(t.id())).toList();
        int active = 0, waiting = 0, doneToday = 0, failed = 0;
        BigDecimal spend = BigDecimal.ZERO;
        for (Task t : everything) { // subtasks cost money too
            if (t.spend() != null && !t.createdAt().isBefore(month)) {
                spend = spend.add(t.spend());
            }
        }
        for (Task t : all) {
            switch (t.status()) {
                case PENDING, RUNNING, WAITING -> active++;
                case NEEDS_CONFIRMATION -> waiting++;
                case DONE -> doneToday += t.finishedAt() != null && !t.finishedAt().isBefore(today) ? 1 : 0;
                case ERROR -> failed++;
                default -> { }
            }
        }
        return new TaskStatsDto(all.size(), active, waiting, doneToday, failed, spend);
    }

    /**
     * Where a task's time went, start to end: waiting before it ran (the scheduler, choosing its model, a free
     * session), each turn broken down, and the gaps between turns, waiting for the user's reply or for a slot again.
     */
    public com.superagent.api.timing.TaskTimingDto timing(String id, String owner) {
        Task task = requireVisible(id, owner);
        List<com.superagent.core.timing.TurnTiming> turns = timings == null ? List.of() : timings.forSessions(List.of(id));
        List<Instant> replies = runStore.listMessages(id).stream().filter(m -> m.role() == MessageRole.USER)
                .map(ChatMessage::createdAt).sorted().toList();
        List<com.superagent.api.timing.TaskTimingDto.Segment> out = new ArrayList<>();
        Instant at = task.createdAt();
        for (com.superagent.core.timing.TurnTiming t : turns) {
            if (t.startedAt().isAfter(at)) {
                String kind = out.isEmpty() ? com.superagent.api.timing.TaskTimingDto.WAIT_START
                        : com.superagent.api.timing.TaskTimingDto.WAIT_QUEUE;
                Instant from = at;
                Instant reply = replies.stream().filter(r -> r.isAfter(from) && !r.isAfter(t.startedAt())).findFirst().orElse(null);
                if (!out.isEmpty() && reply != null) {
                    out.add(com.superagent.api.timing.TaskTimingDto.wait(com.superagent.api.timing.TaskTimingDto.WAIT_USER, from, reply));
                    out.add(com.superagent.api.timing.TaskTimingDto.wait(kind, reply, t.startedAt()));
                } else {
                    out.add(com.superagent.api.timing.TaskTimingDto.wait(kind, from, t.startedAt()));
                }
            }
            out.add(com.superagent.api.timing.TaskTimingDto.turn(com.superagent.api.timing.TurnTimingDto.of(t)));
            at = t.finishedAt().isAfter(at) ? t.finishedAt() : at;
        }
        boolean open = task.status() == TaskStatus.PENDING || task.status() == TaskStatus.RUNNING
                || holdsSession(task.status());
        Instant end = open ? Instant.now() : task.finishedAt() != null ? task.finishedAt() : at;
        if (end.isAfter(at)) {
            String kind = task.status() == TaskStatus.NEEDS_CONFIRMATION ? com.superagent.api.timing.TaskTimingDto.WAIT_USER
                    : task.status() == TaskStatus.RUNNING ? com.superagent.api.timing.TaskTimingDto.RUNNING
                    : out.isEmpty() ? com.superagent.api.timing.TaskTimingDto.WAIT_START : com.superagent.api.timing.TaskTimingDto.WAIT_QUEUE;
            if (open || kind.equals(com.superagent.api.timing.TaskTimingDto.WAIT_START)) {
                out.add(com.superagent.api.timing.TaskTimingDto.wait(kind, at, end));
            }
        }
        return new com.superagent.api.timing.TaskTimingDto(task.createdAt(), end, open, out);
    }

    public TaskDetailDto detail(String id, String owner) {
        Task task = requireVisible(id, owner);
        List<MessageDto> messages = runStore.listMessages(id).stream().map(m -> toMessageDto(id, m)).toList();
        List<TaskEventDto> events = runStore.listEvents(id).stream().map(TaskService::toEventDto).toList();
        List<TaskPhaseDto> phases = TaskKind.fromStorage(task.flow()) == TaskKind.CODING
                ? codingFlow.load(id).stream().map(TaskService::toPhaseDto).toList()
                : List.of();
        return new TaskDetailDto(toDto(task), task.brief(), messages, events, phases);
    }

    /** Events from {@code offset} onward (chronological), for incremental polling of a running task. */
    public List<TaskEventDto> eventsFrom(String id, String owner, int offset) {
        requireVisible(id, owner);
        List<AgentEvent> all = runStore.listEvents(id);
        int from = Math.max(0, offset);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, all.size()).stream().map(TaskService::toEventDto).toList();
    }

    /** Lightweight status/progress snapshot for polling, without the event stream. */
    public TaskDto status(String id, String owner) {
        return toDto(requireVisible(id, owner));
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
        return create(request, owner, agentId, sessionId, messageId, null);
    }

    /**
     * A subtask of the complex task {@code parentId}: it runs as the same agent, side by side with its siblings (as
     * many at once as the system settings allow), and its result goes to the complex task, not to a chat or a to-do.
     */
    public TaskDto createSubtask(String parentId, String owner, String title, String brief) {
        Task parent = require(parentId, owner);
        if (TaskKind.fromStorage(parent.flow()) != TaskKind.COMPLEX) {
            throw new IllegalArgumentException("只有复杂任务能拆子任务");
        }
        if (links == null) {
            throw new IllegalStateException("subtasks need a task link store");
        }
        if (summaryRounds(parentId) >= ComplexTaskPrompts.MAX_ROUNDS) {
            throw new IllegalArgumentException("已经是最后一轮汇总，不能再派子任务了：请直接写出结论，并写明缺失的部分");
        }
        if (subtaskRoom(parentId) <= 0) {
            throw new IllegalArgumentException("子任务已到上限 " + maxSubtasks() + " 个（为失败的子任务补派的不算在内）："
                    + "请用已有的子任务完成工作");
        }
        String agentId = taskAgents == null ? null : taskAgents.agentOf(parentId).orElse(null);
        return create(new CreateTaskRequest(title, brief, parent.source().name(), null, null, null, null,
                parent.agentType().cliName(), TaskKind.GENERAL.storageName()), owner, agentId, null, null, parentId);
    }

    /** How many more subtasks this complex task may hand out: a failed one may be replaced without using up the limit. */
    int subtaskRoom(String parentId) {
        List<Task> kids = children(parentId);
        long failed = kids.stream().filter(k -> k.status() == TaskStatus.ERROR || k.status() == TaskStatus.STOPPED).count();
        return (int) Math.max(0, maxSubtasks() + failed - kids.size());
    }

    private TaskDto create(CreateTaskRequest request, String owner, String agentId, String sessionId, String messageId,
                           String parentId) {
        String id = UUID.randomUUID().toString();
        String who = owner == null || owner.isBlank() ? defaultOwner : owner;
        String continuesFrom = blankToNull(request.continuesFrom());
        if (continuesFrom != null) {
            Task before = require(continuesFrom, who);
            if (agentId == null && taskAgents != null) {
                agentId = taskAgents.agentOf(before.id()).orElse(null); // the same agent carries on its own work
            }
        }
        AgentType agentType = AgentType.fromString(request.agentType());
        String model = blankToNull(request.model());
        // A stand-in until just before it runs: then its model is chosen for the job (TaskModelSelector).
        if (!runners.containsKey(agentType)) {
            // Fail at creation rather than letting the task start and error with "no runner".
            throw new IllegalArgumentException("Agent " + agentType.cliName() + " can't run tasks here yet; available: "
                    + runners.keySet().stream().map(AgentType::cliName).sorted().toList());
        }
        TaskSource source = parseSource(request.source());
        TaskKind kind = parentId != null ? TaskKind.GENERAL : resolveKind(request); // one level: a subtask doesn't split
        Instant now = Instant.now();
        // No workspace yet: it comes from the session this task is scheduled onto.
        // The kind is stored in the (otherwise unused) flow column — zero migration.
        Task task = new Task(id, cleanTitle(request.title()), blankToNull(request.brief()),
                source,
                blankToNull(request.repo()), blankToNull(request.branch()), kind.storageName(),
                TaskStatus.PENDING, 0, BigDecimal.ZERO, model, agentType,
                null, who, null, now, null, null, now, null);
        tasks.save(task);
        if (links != null && (parentId != null || continuesFrom != null)) {
            // Before the scheduler can see it: admission holds subtasks back to the parallel limit.
            links.save(new com.superagent.core.task.TaskLink(id, parentId, continuesFrom, now));
        }
        if (kind == TaskKind.CODING) {
            codingFlow.persist(id, CodingFlow.initialPhases());
        }
        if (agentId != null && skills != null) {
            skills.bindTaskToAgent(id, agentId); // before scheduling, so the first turn already has them
        }
        if (agentId != null && taskAgents != null) {
            taskAgents.link(id, agentId, sessionId, messageId); // so the agent's panel and chat can show the work it set off
        }
        if (projects != null) {
            // A task belongs to the project of the agent that set it off; a subtask or follow-on, to its origin's.
            String from = parentId != null ? parentId : continuesFrom;
            String via = agentId != null ? agentId
                    : from == null || taskAgents == null ? null : taskAgents.agentOf(from).orElse(null);
            projects.recordTask(id, via);
        }
        if ((selector == null || !selector.enabled()) && choices != null) {
            // Nothing will judge this task (admit skips choosing): say now why it runs on what it runs on.
            choices.save(defaultChoice(task, request, agentId, now));
        }
        log.info("created task {} ({}), kind={}{}", id, task.title(), kind.storageName(),
                parentId != null ? ", subtask of " + parentId : continuesFrom != null ? ", carrying on " + continuesFrom : "");
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
        List<Task> all = tasks.listTasks();
        List<Task> waiting = all.stream()
                .filter(t -> t.status() == TaskStatus.PENDING && t.workspaceDir() == null)
                .sorted(Comparator.comparing(Task::createdAt)).toList();
        Set<String> ids = new java.util.HashSet<>();
        waiting.forEach(t -> ids.add(t.id()));
        String company = TenantContext.require();
        admitted.entrySet().removeIf(e -> company.equals(e.getValue()) && !ids.contains(e.getKey())); // started, stopped or deleted since
        Map<String, String> parents = parentsOf();
        Map<String, Integer> room = new HashMap<>();
        List<Task> out = new ArrayList<>();
        for (Task t : waiting) {
            if (admitted.containsKey(t.id())) {
                continue;
            }
            String parent = parents.get(t.id());
            if (parent == null) {
                out.add(t);
                continue;
            }
            // A complex task's subtasks: no more running at once than the system settings allow.
            int free = room.computeIfAbsent(parent, p -> maxParallel() - (int) all.stream()
                    .filter(k -> p.equals(parents.get(k.id())))
                    .filter(k -> k.status() == TaskStatus.RUNNING || admitted.containsKey(k.id())
                            || (k.status() == TaskStatus.PENDING && k.workspaceDir() != null))
                    .count());
            if (free > 0) {
                out.add(t);
                room.put(parent, free - 1);
            }
        }
        return out;
    }

    /**
     * Complex tasks whose subtasks have all finished carry on: they sum up. Run by the scheduler on every pass, so it
     * happens after a restart too.
     */
    void sumUpFinished() {
        if (links == null) {
            return;
        }
        for (Task t : tasks.listTasks()) {
            if (t.status() != TaskStatus.WAITING) {
                continue;
            }
            List<Task> kids = children(t.id());
            if (kids.stream().allMatch(this::finished)) {
                sumUp(t, kids);
            }
        }
    }

    private void sumUp(Task parent, List<Task> kids) {
        List<ComplexTaskPrompts.Result> results = kids.stream()
                .map(k -> new ComplexTaskPrompts.Result(k, lastAnswer(k.id()), k.error())).toList();
        int round = summaryRounds(parent.id()) + 1;
        String prompt = ComplexTaskPrompts.summary(results, round, round < ComplexTaskPrompts.MAX_ROUNDS ? subtaskRoom(parent.id()) : 0);
        log.info("complex task {}: its {} subtasks have finished, summing up (round {})", parent.id(), kids.size(), round);
        carryOn(parent.id(), prompt);
    }

    /**
     * Continues a waiting complex task in its own session with {@code prompt}, kept as its next message (so a restart
     * that drops the queue resumes from it). Does nothing once the task has moved on.
     */
    private void carryOn(String taskId, String prompt) {
        synchronized (schedulerLock) {
            Task task = tasks.findTask(taskId).filter(t -> t.status() == TaskStatus.WAITING).orElse(null);
            if (task == null) {
                return;
            }
            String sessionId = boundSessionId(task);
            runStore.appendMessage(new ChatMessage(UUID.randomUUID().toString(), taskId, MessageRole.USER, prompt, Instant.now()));
            TaskSessionPool.Lease lease = pool.acquireSession(task.createdBy(), task.agentType(), taskId, sessionId, task.workspaceDir());
            if (lease != null) {
                resumeRun(task, lease, prompt);
            } else {
                tasks.save(task.withStatus(TaskStatus.PENDING));
                queues.computeIfAbsent(task.createdBy(), k -> new ArrayDeque<>())
                        .addLast(new QueuedRun(taskId, prompt, false, sessionId));
            }
        }
    }

    /** Done with, as far as its complex task is concerned: finished, stopped, or failed with no retries left. */
    boolean finished(Task t) {
        return switch (t.status()) {
            case DONE, STOPPED, DELETED -> true;
            case ERROR -> runStates.find(t.id()).map(r -> r.attemptCount() >= MAX_ATTEMPTS).orElse(false);
            default -> false;
        };
    }

    /** Whether the complex task handed out subtasks since it last summed up (so this turn was a plan, not a conclusion). */
    private boolean handedOutWork(Task task) {
        Instant lastSummary = runStore.listMessages(task.id()).stream()
                .filter(m -> m.role() == MessageRole.USER && m.content() != null
                        && m.content().startsWith(ComplexTaskPrompts.SUMMARY_MARK))
                .map(ChatMessage::createdAt).max(Comparator.naturalOrder()).orElse(Instant.MIN);
        return children(task.id()).stream().anyMatch(k -> k.createdAt().isAfter(lastSummary));
    }

    /** The subtasks' results as the complex task's messages count them: one summing-up round per mark. */
    private int summaryRounds(String taskId) {
        return (int) runStore.listMessages(taskId).stream()
                .filter(m -> m.role() == MessageRole.USER && m.content() != null
                        && m.content().startsWith(ComplexTaskPrompts.SUMMARY_MARK)).count();
    }

    /** The task's last reply: what it came up with. */
    String lastAnswer(String taskId) {
        List<ChatMessage> messages = runStore.listMessages(taskId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.role() == MessageRole.ASSISTANT && m.content() != null && !m.content().isBlank()) {
                return m.content();
            }
        }
        return null;
    }

    /** A complex task's subtasks, oldest first, deleted ones left out. */
    List<Task> children(String parentId) {
        if (links == null) {
            return List.of();
        }
        return links.children(parentId).stream().map(tasks::findTask).flatMap(java.util.Optional::stream)
                .filter(t -> t.status() != TaskStatus.DELETED).toList();
    }

    /** Subtask id → its complex task. */
    private Map<String, String> parentsOf() {
        Map<String, String> out = new HashMap<>();
        if (links != null) {
            links.all().stream().filter(l -> l.parentId() != null).forEach(l -> out.put(l.taskId(), l.parentId()));
        }
        return out;
    }

    private boolean isSubtask(String taskId) {
        return links != null && links.find(taskId).map(l -> l.parentId() != null).orElse(false);
    }

    private static boolean isComplex(Task t) {
        return TaskKind.fromStorage(t.flow()) == TaskKind.COMPLEX;
    }

    /** A task that keeps its session between turns: paused on a question, or a complex task waiting on its subtasks. */
    private static boolean holdsSession(TaskStatus s) {
        return s == TaskStatus.NEEDS_CONFIRMATION || s == TaskStatus.WAITING;
    }

    private int maxSubtasks() {
        return settings == null ? com.superagent.platform.settings.SettingsService.DEFAULT_MAX_SUBTASKS : settings.complexMaxSubtasks();
    }

    private int maxParallel() {
        return settings == null ? com.superagent.platform.settings.SettingsService.DEFAULT_MAX_PARALLEL : settings.complexMaxParallel();
    }

    /**
     * Takes a new task up: first its model, when none was decided yet (a model is asked, so on a
     * worker thread), then a session. Where task dependencies will be checked, when there are some.
     */
    void admit(Task task) {
        if (admitted.putIfAbsent(task.id(), TenantContext.require()) != null) {
            return;
        }
        boolean decided = choices == null || !choices.list(task.id()).isEmpty();
        if (selector == null || !selector.enabled() || decided) {
            schedule(task);
            return;
        }
        executor.execute(TenantContext.wrap(() -> {
            try {
                chooseModel(task);
            } catch (RuntimeException e) {
                log.warn("choosing a model for task {} failed; it runs on {}", task.id(), task.model(), e);
            }
            tasks.findTask(task.id()).filter(t -> t.status() == TaskStatus.PENDING && t.workspaceDir() == null)
                    .ifPresentOrElse(this::schedule, () -> admitted.remove(task.id()));
        }));
    }

    private void chooseModel(Task task) {
        // A complex task's plan and conclusion decide everything; its subtasks are each matched to a cheap model.
        var choice = (isComplex(task) ? selector.strongest(task).or(() -> selector.choose(task)) : selector.choose(task)).orElse(null);
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
        requireVisible(id, owner);
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
        executor.execute(TenantContext.wrap(() -> run(running, prompt, session.id, lease.firstTurn(), wrapBrief)));
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
        executor.execute(TenantContext.wrap(() -> run(running, prompt, sessionId, firstTurn, false)));
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
        if (isComplex(waiting)) {
            children(id).stream().filter(k -> !finished(k)).forEach(k -> stop(k.id(), owner));
            if (waiting.status() == TaskStatus.WAITING) {
                settle(waiting, TaskStatus.STOPPED, waiting.progress(), null, BigDecimal.ZERO);
                return;
            }
        }
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
        children(id).forEach(k -> delete(k.id(), owner));
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
        executor.execute(TenantContext.wrap(() -> run(running, prompt, sid, ft, false)));
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
        return executeTurn(task, prompt, agentSessionId, sessionFirstTurn, wrapBriefPrompt, onToolUse, cancellation, null);
    }

    /** {@code label}: what the turn is, for its timing (a coding phase's key), or null. */
    private TurnResult executeTurn(Task task, String prompt, String agentSessionId, boolean sessionFirstTurn,
                                   boolean wrapBriefPrompt, Runnable onToolUse, Cancellation cancellation, String label) {
        com.superagent.core.timing.TurnTimer timer = new com.superagent.core.timing.TurnTimer();
        String turnId = UUID.randomUUID().toString();
        try {
            return timedTurn(task, prompt, agentSessionId, sessionFirstTurn, wrapBriefPrompt, onToolUse, cancellation,
                    timer, turnId);
        } finally {
            if (timings != null) {
                try {
                    timings.save(timer.finish(task.id(), turnId, label));
                } catch (RuntimeException e) {
                    log.warn("could not keep the timing of a turn of task {}: {}", task.id(), e.getMessage());
                }
            }
        }
    }

    private TurnResult timedTurn(Task task, String prompt, String agentSessionId, boolean sessionFirstTurn,
                                 boolean wrapBriefPrompt, Runnable onToolUse, Cancellation cancellation,
                                 com.superagent.core.timing.TurnTimer timer, String messageId) {
        AgentRunner runner = runners.get(task.agentType());
        if (runner == null) {
            throw new IllegalStateException("No runner for agent type " + task.agentType());
        }
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<BigDecimal> turnCost = new AtomicReference<>(BigDecimal.ZERO);
        AtomicReference<String> error = new AtomicReference<>();

        // The task's spend: our estimate from its tokens, for every CLI; what the CLI says only where we have no price.
        AtomicReference<BigDecimal> estimated = new AtomicReference<>();
        com.superagent.core.usage.UsageContext paying = new com.superagent.core.usage.UsageContext(task.createdBy(),
                com.superagent.core.usage.UsageContext.SOURCE_TASK,
                taskAgents == null ? null : taskAgents.agentOf(task.id()).orElse(null), task.id(), null, task.agentType().cliName());
        AgentEventSink sink = timer.tapTools(event -> {
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
        });

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
        if (grant != null && isComplex(task)) {
            tools = new ArrayList<>(tools);
            tools.addAll(ComplexTaskMcpTools.ALLOWED);
        }
        String cli = task.agentType().cliName();
        CredentialService.Choice choice = credentials == null ? new CredentialService.Choice(null) : credentials.current(cli);
        AgentRunRequest request = new AgentRunRequest(
                agentSessionId, sessionFirstTurn ? null : agentSessionId, task.workspaceDir(),
                wrapBriefPrompt ? wrapBrief(task, prompt) : prompt,
                effectiveModel(task),
                tools, skipPermissions, McpGrants.withServers(grant == null ? null : mcpGrants.mcpConfig(grant), connected),
                taskSystemPrompt(task, grant != null),
                nextTurnImages.remove(task.id()), null, choice.credential());
        try {
            timer.attemptStarted();
            try {
                runner.run(request, sink, cancellation);
            } finally {
                timer.attemptEnded();
            }
            // The endpoint or key failed before the agent produced anything: switch keys and run the turn again.
            boolean nothingSaid = text.isEmpty() && result.get() == null;
            if (credentials != null && nothingSaid && error.get() != null && !cancellation.isCancelled()) {
                java.util.Optional<CredentialService.Choice> next = credentials.failover(cli, choice, error.get());
                if (next.isPresent()) {
                    log.info("task {}: retrying the turn on endpoint {}", task.id(), next.get().id());
                    error.set(null);
                    timer.attemptStarted();
                    try {
                        runner.run(request.withCredential(next.get().credential()), sink, cancellation);
                    } finally {
                        timer.attemptEnded();
                    }
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
        } else if (turn.needsConfirmation() && !onItsOwn(task)) {
            settle(task, TaskStatus.NEEDS_CONFIRMATION, 50, null, turn.cost());
        } else if (isComplex(task) && handedOutWork(task)) {
            // Its subtasks run (some may already be done); it sums up once they all are.
            settle(task, TaskStatus.WAITING, 50, null, turn.cost());
        } else {
            writeOutput(task, turn.finalText());
            settle(task, TaskStatus.DONE, 100, null, turn.cost());
        }
    }

    /**
     * A complex task and its subtasks run without stopping for the user: they were told to go with their recommended
     * option, and a question one asks anyway is taken as its answer.
     */
    private boolean onItsOwn(Task task) {
        return isComplex(task) || isSubtask(task.id());
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
                TurnResult turn = executeTurn(task, prompt, agentSessionId, firstTurn, false, null, cancellation, phase.key());
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
                        TurnResult retry = executeTurn(task, CodingFlow.PRD_RETRY_PROMPT, agentSessionId, false, false, null, cancellation, phase.key());
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
        Task next = holdsSession(status)
                ? latest.withStatus(status).withProgress(progress)
                : latest.finished(status, progress, error);
        BigDecimal spend = (latest.spend() == null ? BigDecimal.ZERO : latest.spend())
                .add(turnCost == null ? BigDecimal.ZERO : turnCost);
        tasks.save(next.withSpend(spend));
        log.info("task {} -> {} ({}%), spend={}", task.id(), status, next.progress(), spend);
        publish(next, null);

        synchronized (schedulerLock) {
            TaskSessionPool.Session session = pool.sessionForTask(task.id());
            if (session != null) {
                if (holdsSession(status)) {
                    pool.pause(session);
                } else {
                    pool.release(session);
                }
            }
        }
        tryDispatch(task.createdBy());
        if (isSubtask(task.id()) || status == TaskStatus.WAITING) {
            wake(); // a sibling may start now, or the complex task sum up
        }
    }

    private void wake() {
        Runnable w = wakeScheduler;
        if (w != null) {
            w.run();
        } else {
            sumUpFinished(); // no scheduler (tests): at once
        }
    }

    /** Tells the to-do page (and anyone else listening) the task's status; {@code agentId} only at creation. */
    private void publish(Task t, String agentId) {
        if (events != null && !isSubtask(t.id())) {
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

    /**
     * The first turn: a complex task is told to split the work, a subtask that it is one part of a bigger job, and a
     * follow-up first reads what the task it carries on came to.
     */
    private String wrapBrief(Task task, String brief) {
        String body = carryOnFrom(task) + (brief == null ? "" : brief);
        String parent = links == null ? null : links.find(task.id()).map(com.superagent.core.task.TaskLink::parentId).orElse(null);
        if (parent != null) {
            Task p = tasks.findTask(parent).orElse(null);
            if (p != null) {
                return ComplexTaskPrompts.subtask(p, body);
            }
        }
        if (isComplex(task)) {
            return ComplexTaskPrompts.plan(body, maxSubtasks(), maxParallel());
        }
        return wrapBrief(body);
    }

    /** For a follow-up: the task it carries on and the ones before that (up to five back). */
    private String carryOnFrom(Task task) {
        if (links == null) {
            return "";
        }
        List<ComplexTaskPrompts.Result> chain = new ArrayList<>();
        String from = links.find(task.id()).map(com.superagent.core.task.TaskLink::continuesFrom).orElse(null);
        Set<String> seen = new java.util.HashSet<>();
        while (from != null && chain.size() < 5 && seen.add(from)) {
            Task before = tasks.findTask(from).orElse(null);
            if (before == null) {
                break;
            }
            chain.add(new ComplexTaskPrompts.Result(before, lastAnswer(before.id()), before.error()));
            from = links.find(before.id()).map(com.superagent.core.task.TaskLink::continuesFrom).orElse(null);
        }
        return ComplexTaskPrompts.carryOn(chain);
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

    /**
     * As {@link #require}, but also for members of the project the task was made in: they can see every task in it,
     * while acting on one (steering, stopping, deleting) stays with whoever started it.
     */
    private Task requireVisible(String id, String user) {
        return tasks.findTask(id)
                .filter(t -> t.status() != TaskStatus.DELETED)
                .filter(t -> (t.createdBy() != null && t.createdBy().equals(user))
                        || (projects != null && projects.visibleThroughProject(com.superagent.core.project.ProjectItemKind.TASK, id, user)))
                .orElseThrow(() -> new TaskNotFoundException(id));
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
        return toDto(t, t.workspaceDir() == null ? null : boundSessionId(t), null, viewsOf(t));
    }

    /** List view: session id from the workspace name only, so listing N tasks doesn't cost N run-state reads. */
    private TaskDto toListDto(Task t, Views v) {
        return toDto(t, t.workspaceDir() == null ? null : deriveSessionId(t), null, v);
    }

    private TaskDto toDto(Task t, String sessionId, String originMessageId, Views v) {
        List<Task> kids = v.kids(t.id());
        String agentId = v.agentOf(t.id());
        com.superagent.core.task.TaskLink link = v.link(t.id());
        return new TaskDto(t.id(), t.title(), t.source().name().toLowerCase(),
                t.repo(), t.branch(), t.flow(), t.status().name().toLowerCase(),
                t.progress(), t.spend(), t.model(), t.createdBy(), t.error(),
                t.createdAt(), t.startedAt(), t.finishedAt(), t.updatedAt(),
                t.agentType().name().toLowerCase(), sessionId, t.workspaceDir(), sessionState(t.id(), sessionId), t.tier(),
                originMessageId, link == null ? null : link.parentId(), link == null ? null : link.continuesFrom(),
                v.tags(t.id()), agentId, agentId == null ? null : v.agentName(agentId),
                kids.size(), (int) kids.stream().filter(this::finished).count());
    }

    // --- relations and tags ---

    /** The complex task it is part of, what it carries on from and what carries on from it, and its subtasks. */
    public com.superagent.api.task.TaskRelationsDto relations(String id, String owner) {
        Task task = requireVisible(id, owner);
        Views v = views();
        com.superagent.core.task.TaskLink link = v.link(id);
        java.util.function.Function<String, TaskDto> dto = other -> other == null ? null
                : tasks.findTask(other).filter(t -> t.status() != TaskStatus.DELETED).map(t -> toListDto(t, v)).orElse(null);
        List<TaskDto> followUps = links == null ? List.of()
                : links.followUps(id).stream().map(dto).filter(java.util.Objects::nonNull).toList();
        List<TaskDto> subtasks = v.kids(id).stream().map(t -> toListDto(t, v)).toList();
        return new com.superagent.api.task.TaskRelationsDto(dto.apply(link == null ? null : link.parentId()),
                dto.apply(link == null ? null : link.continuesFrom()), followUps, subtasks, v.totalSpend(task));
    }

    /** What a subtask of {@code parentId} came up with, in full (or why it failed). */
    public String subtaskOutput(String parentId, String childId, String owner) {
        requireVisible(parentId, owner);
        Task child = children(parentId).stream().filter(k -> k.id().equals(childId)).findFirst()
                .orElseThrow(() -> new TaskNotFoundException(childId));
        String out = lastAnswer(childId);
        if (out != null) {
            return out;
        }
        return child.status() == TaskStatus.ERROR ? "失败：" + child.error() : "（还没有结果，状态 " + child.status() + "）";
    }

    /** Replaces the task's topic tags (trimmed, deduplicated, at most {@value #MAX_TAGS}). */
    public TaskDto setTags(String id, String owner, List<String> given) {
        Task task = require(id, owner);
        if (tagStore == null) {
            throw new IllegalStateException("tags need a tag store");
        }
        tagStore.set(id, cleanTags(given));
        return toDto(task);
    }

    static final int MAX_TAGS = 8;

    static List<String> cleanTags(List<String> given) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String t : given == null ? List.<String>of() : given) {
            String c = t == null ? "" : t.strip().replaceAll("[\\s#]+", " ").strip();
            if (!c.isEmpty() && c.length() <= 20 && out.size() < MAX_TAGS) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /** What a list view needs about many tasks at once, read once per request. */
    private Views views() {
        Map<String, com.superagent.core.task.TaskLink> byTask = new HashMap<>();
        Map<String, List<Task>> kids = new HashMap<>();
        if (links != null) {
            List<com.superagent.core.task.TaskLink> all = links.all();
            all.forEach(l -> byTask.put(l.taskId(), l));
            if (all.stream().anyMatch(l -> l.parentId() != null)) {
                Map<String, Task> byId = new HashMap<>();
                tasks.listTasks().forEach(t -> byId.put(t.id(), t));
                all.stream().filter(l -> l.parentId() != null).sorted(Comparator.comparing(com.superagent.core.task.TaskLink::createdAt))
                        .forEach(l -> {
                            Task k = byId.get(l.taskId());
                            if (k != null && k.status() != TaskStatus.DELETED) {
                                kids.computeIfAbsent(l.parentId(), p -> new ArrayList<>()).add(k);
                            }
                        });
            }
        }
        return new Views(byTask, kids, tagStore == null ? Map.of() : tagStore.all());
    }

    /** As {@link #views()}, for one task only (a detail page polling its status). */
    private Views viewsOf(Task t) {
        Map<String, com.superagent.core.task.TaskLink> byTask = new HashMap<>();
        Map<String, List<Task>> kids = new HashMap<>();
        if (links != null) {
            links.find(t.id()).ifPresent(l -> byTask.put(t.id(), l));
            if (isComplex(t)) {
                kids.put(t.id(), children(t.id()));
            }
        }
        return new Views(byTask, kids, tagStore == null ? Map.of() : Map.of(t.id(), tagStore.tags(t.id())));
    }

    private final class Views {
        private final Map<String, com.superagent.core.task.TaskLink> links;
        private final Map<String, List<Task>> kids;
        private final Map<String, List<String>> tags;
        private final Map<String, java.util.Optional<String>> agents = new HashMap<>();
        private final Map<String, String> names = new HashMap<>();

        Views(Map<String, com.superagent.core.task.TaskLink> links, Map<String, List<Task>> kids, Map<String, List<String>> tags) {
            this.links = links;
            this.kids = kids;
            this.tags = tags;
        }

        com.superagent.core.task.TaskLink link(String id) {
            return links.get(id);
        }

        boolean isChild(String id) {
            com.superagent.core.task.TaskLink l = links.get(id);
            return l != null && l.parentId() != null;
        }

        List<Task> kids(String id) {
            return kids.getOrDefault(id, List.of());
        }

        List<String> tags(String id) {
            return tags.getOrDefault(id, List.of());
        }

        String agentOf(String id) {
            return taskAgents == null ? null
                    : agents.computeIfAbsent(id, k -> taskAgents.agentOf(k)).orElse(null);
        }

        String agentName(String agentId) {
            return names.computeIfAbsent(agentId, a -> doers == null ? a : doers.name(a).orElse(a));
        }

        BigDecimal totalSpend(Task t) {
            BigDecimal sum = t.spend() == null ? BigDecimal.ZERO : t.spend();
            for (Task k : kids(t.id())) {
                sum = sum.add(k.spend() == null ? BigDecimal.ZERO : k.spend());
            }
            return sum;
        }
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
