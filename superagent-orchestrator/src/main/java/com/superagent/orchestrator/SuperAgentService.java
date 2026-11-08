package com.superagent.orchestrator;

import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.provider.CredentialService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.superagent.agent.AgentService;
import com.superagent.agent.chat.ChatService;
import com.superagent.agent.log.AgentLogService;
import com.superagent.agent.memory.AgentMemoryService;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.superagent.SuperAgentPlanDto;
import com.superagent.api.superagent.SuperAgentStateDto;
import com.superagent.api.superagent.SuperAgentWorkDto;
import com.superagent.api.superagent.SuperAgentWorkEventDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.agent.SuperAgentState;
import com.superagent.core.agent.SuperAgentStateStore;
import com.superagent.core.agent.SuperAgentWork;
import com.superagent.core.agent.SuperAgentWorkEvent;
import com.superagent.core.agent.SuperAgentWorkStore;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.skills.SkillService;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.task.TaskService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Each user's SuperAgent, in two parts that never share a conversation.
 *
 * <p><b>The chat.</b> The user talks with it live, like any agent ({@link SuperAgentChat} gives those turns its
 * character and team tools); it answers and acts right there.
 *
 * <p><b>The background.</b> What arrives on its own — task results, messages the user's data sources receive — is
 * queued in a session of its own ({@link #backgroundSession}). The loop ({@link SuperAgentLoop}) checks every few
 * seconds; once new input has gone quiet for {@code quiet} (or has waited {@code max-wait}), everything since the
 * last round is handed to a background run as one batch, one round at a time per user.
 * {@link SuperAgentState#processedUntil()} is the cursor. What the user needs to know the round posts into the
 * chat (tell_user, marked as coming from the background); the rest stays in the background record.
 *
 * <p><b>Plan first.</b> Hiring, re-briefing members and assigning tasks are refused until the user approves the
 * latest plan in the UI — enforced here, not just asked of the model.
 *
 * <p><b>The outside world.</b> Received messages ({@link #deliverExternal}) are marked as third-party data and
 * worked in rounds of their own, which can't hire, assign or reply to tasks: text from strangers never runs where
 * it could act.
 */
@Service
public class SuperAgentService {

    private static final Logger log = LoggerFactory.getLogger(SuperAgentService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final int REPORT_CHARS = 1500;

    /** Tool names as the CLI sees them; a background round may call these and nothing else. */
    static final List<String> BACKGROUND_TOOLS = Stream.concat(
            SuperAgentTools.NAMES.stream().map(n -> "mcp__superagent__" + n), McpGrants.INBOX_TOOLS.stream()).toList();
    /**
     * The chat answers the user directly, so it has no use for posting into its own conversation; it alone manages
     * collaborators, works on files and starts tasks directly (a background round reads third-party messages, which must not bring people
     * in or touch anyone's files).
     */
    static final List<String> CHAT_TOOLS = Stream.concat(BACKGROUND_TOOLS.stream()
            .filter(t -> !t.equals("mcp__superagent__" + SuperAgentTools.TELL_USER)),
            Stream.of(SuperAgentCollabTools.TOOLS, SuperAgentTaskTools.TOOLS, McpGrants.SUPER_FILE_TOOLS, McpGrants.SKILL_TOOLS,
                    McpGrants.SKILL_CHAT_TOOLS, McpGrants.DOC_READ_TOOLS, com.superagent.agent.AgentSkillMcpTools.TOOLS).flatMap(List::stream)).toList();

    /** Marks a delivery of received messages; such rounds can't touch the team or its tasks. */
    static final String EXTERNAL = "【外部消息】";
    static final Set<String> ACTING_TOOLS = Set.of("hire_member", "update_member", "place_member", "assign_task", "reply_task");
    static final List<String> EXTERNAL_ROUND_TOOLS = BACKGROUND_TOOLS.stream()
            .filter(t -> !ACTING_TOOLS.contains(t.substring("mcp__superagent__".length()))).toList();

    /** The author of what the background posts into the chat, so the page can mark it and the chat can see it. */
    public static final String BACKGROUND_AUTHOR = "superagent:background";

    /** A task the SuperAgent assigned; {@code reported} is the last status it told the user about. */
    /** Recognizes a task the SuperAgent assigned (its brief opens with this), to back-fill the work record. */
    private static final Pattern ASSIGNED_BRIEF = Pattern.compile("^你是团队成员「(.+?)」，由 SuperAgent 分派本任务");
    private static final int SUMMARY_CHARS = 300;

    /** A member as the tools describe it. */
    record TeamMember(String id, String name, String persona, String model, List<String> skillIds, boolean hired,
                      String project, String parent) {
    }

    /** Clears a member's project or parent when given as its value. */
    static final String NONE = "none";

    private com.superagent.project.ProjectService projects; // null in tests: no projects

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjects(com.superagent.project.ProjectService projects) {
        this.projects = projects;
    }

    private final AgentService agents;
    private final SuperAgentStateStore states;
    private final ChatStore chats;
    private final TaskService taskService;
    private final TaskStore tasks;
    private final ProviderService providers;
    private final SettingsService settings;
    private final McpGrants grants;
    private final ChatService chatService;
    private final AgentMemoryService memory;
    private final SuperAgentWorkStore works;
    private final AgentLogService runLog;
    private final ApplicationEventPublisher events;
    private final SkillService skills;
    private final com.superagent.agent.AgentInviteService inviteService;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private com.superagent.core.provider.ModelRouter modelRouter; // null in tests: the default model

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setModelRouter(com.superagent.core.provider.ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }
    private com.superagent.core.usage.UsageRecorder usage; // null in tests: tokens aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageRecorder(com.superagent.core.usage.UsageRecorder usage) {
        this.usage = usage;
    }

    private CredentialService credentials; // null in tests: runs use the server's environment


    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCredentials(CredentialService credentials) {
        this.credentials = credentials;
    }
    private final Duration quiet;
    private final Duration maxWait;
    private final int teamLimit;
    private final ZoneId zone;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Object stateLock = new Object();
    /** owner → cutoff of the background round running for them. */
    private final Map<String, Instant> running = new ConcurrentHashMap<>();
    /**
     * MCP grants of background rounds, by token → whether the round works received messages (then it may not
     * touch the team or its tasks). Per run, not per user: the chat may act while the background reads.
     */
    private final Map<String, Boolean> backgroundGrants = new ConcurrentHashMap<>();
    /** Owners whose next background round should start without waiting for quiet (an urgent delivery). */
    private final Set<String> immediate = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "superagent-round");
        t.setDaemon(true);
        return t;
    });

    public SuperAgentService(AgentService agents, SuperAgentStateStore states, ChatStore chats, TaskService taskService,
                             TaskStore tasks, ProviderService providers, SettingsService settings,
                             McpGrants grants, SkillService skills, com.superagent.agent.AgentInviteService inviteService,
                             ChatService chatService, AgentMemoryService memory, SuperAgentWorkStore works,
                             AgentLogService runLog, ApplicationEventPublisher events,
                             List<AgentRunner> agentRunners,
                             @Value("${superagent.superagent.quiet-seconds:10}") long quietSeconds,
                             @Value("${superagent.superagent.max-wait-seconds:60}") long maxWaitSeconds,
                             @Value("${superagent.superagent.team-limit:6}") int teamLimit,
                             @Value("${superagent.superagent.timezone:Asia/Shanghai}") String timezone) {
        this.agents = agents;
        this.events = events;
        this.states = states;
        this.chats = chats;
        this.taskService = taskService;
        this.tasks = tasks;
        this.providers = providers;
        this.settings = settings;
        this.grants = grants;
        this.chatService = chatService;
        this.memory = memory;
        this.works = works;
        this.runLog = runLog;
        this.skills = skills;
        this.inviteService = inviteService;
        for (AgentRunner r : agentRunners) {
            runners.put(r.type(), r);
        }
        this.quiet = Duration.ofSeconds(Math.max(0, quietSeconds));
        this.maxWait = Duration.ofSeconds(Math.max(quietSeconds, maxWaitSeconds));
        this.teamLimit = Math.max(1, teamLimit);
        this.zone = ZoneId.of(timezone);
    }

    // --- user side ---

    public SuperAgentStateDto state(String owner) {
        SuperAgentState s = ensureState(owner);
        Agent sa = agents.require(s.agentId(), owner);
        Instant cutoff = running.get(owner);
        return new SuperAgentStateDto(sa.id(), sessionId(sa), s.processedUntil(), cutoff != null, cutoff,
                plan(s), team(owner, sa).size(), teamLimit);
    }

    /**
     * Received messages for the owner's SuperAgent; an urgent delivery starts a round right away. The text says
     * what kind of batch it is and lists the messages as quoted data.
     */
    public void deliverExternal(String owner, String text, boolean urgent) {
        SuperAgentState s = ensureState(owner);
        note(backgroundSession(agents.require(s.agentId(), owner)).id(), EXTERNAL + text);
        if (urgent) {
            immediate.add(owner);
        }
    }

    static boolean external(ChatMessage m) {
        return m.role() == MessageRole.SYSTEM && m.content().startsWith(EXTERNAL);
    }

    /** The user approves the latest plan: hiring and assigning are unlocked; the page carries on in the chat. */
    public SuperAgentStateDto approvePlan(String owner, String planId) {
        synchronized (stateLock) {
            SuperAgentState s = ensureState(owner);
            if (!Objects.equals(s.planId(), planId) || !SuperAgentState.PLAN_PROPOSED.equals(s.planStatus())) {
                throw new IllegalArgumentException("This plan is no longer waiting for approval");
            }
            states.save(s.withPlan(s.planId(), SuperAgentState.PLAN_APPROVED, s.planJson()));
            events.publishEvent(new NoticeEvents.PlanChanged(owner, planId, null, false));
            logSuper(owner, "info", "你确认了计划 #" + planId + "，开始执行", null);
        }
        return state(owner);
    }

    private SuperAgentState ensureState(String owner) {
        synchronized (stateLock) {
            Agent sa = agents.ensureSuperAgent(owner);
            SuperAgentState s = states.find(owner).orElse(null);
            if (s == null || !s.agentId().equals(sa.id())) {
                // Start the cursor now: history from before the inbox existed isn't replayed as new work.
                s = states.save(new SuperAgentState(owner, sa.id(), Instant.now(), null, null, null, null, Instant.now()));
            }
            return s;
        }
    }

    // --- the loop (driven by SuperAgentLoop) ---

    void tick(Instant now) {
        for (SuperAgentState s : states.list()) {
            try {
                reportTrackedTasks(s.owner());
                maybeStartRound(s.owner(), now);
            } catch (RuntimeException e) {
                // One user's broken state must not stop everyone else's SuperAgent.
                log.warn("superagent tick failed for {}: {}", s.owner(), e.getMessage(), e);
            }
        }
    }

    private void maybeStartRound(String owner, Instant now) {
        if (running.containsKey(owner)) {
            return;
        }
        SuperAgentState s = states.find(owner).orElse(null);
        if (s == null) {
            return;
        }
        Agent sa = agents.findSuperAgent(owner).orElse(null);
        if (sa == null) {
            return;
        }
        List<ChatMessage> all = SuperAgentInbox.pending(chats.listMessages(backgroundSession(sa).id()), s.processedUntil());
        if (!SuperAgentInbox.due(all, now, quiet, maxWait, immediate.contains(owner))) {
            return;
        }
        immediate.remove(owner);
        // Received messages and the system's own notes never share a round (see the class comment).
        boolean external = external(all.get(0));
        List<ChatMessage> pending = all.stream().takeWhile(m -> external(m) == external).toList();
        if (pending.size() < all.size()) {
            immediate.add(owner); // the rest is due already: take it next tick
        }
        Instant last = pending.get(pending.size() - 1).createdAt();
        if (running.putIfAbsent(owner, last) != null) {
            return;
        }
        executor.execute(TenantContext.wrap(() -> {
            try {
                runRound(owner, sa, pending, last);
            } finally {
                running.remove(owner);
            }
        }));
    }

    /** How one attempt at a round went; {@code error} set + blank {@code text} = it failed. */
    private record RoundAttempt(ChatSession session, AgentType type, String text, String error, String agentSessionId) {
        boolean failed() {
            return text.isBlank() && error != null;
        }
    }

    /**
     * Works one batch in the background session — its own CLI conversation, apart from the chat. What the user
     * needs to know it posts into the chat itself (tell_user); the round's reply stays in the background record.
     * A failed round starts the next one on a fresh CLI conversation. The cursor moves on either way, so a broken
     * batch can't be retried forever.
     */
    private void runRound(String owner, Agent sa, List<ChatMessage> batch, Instant cutoff) {
        long startedAt = System.currentTimeMillis();
        // Like the chat: the admin's default model, or its stand-in while it can't run.
        List<String> usable = modelRouter == null ? List.of() : modelRouter.chatModels();
        String model = usable.isEmpty() ? settings.agentDefaultModel() : usable.get(0);
        ChatSession session = backgroundSession(sa);
        String replyId = UUID.randomUUID().toString();
        RoundAttempt attempt = attemptRound(owner, sa, session, model, batch, memory.read(sa), replyId);
        String reply = attempt.failed() ? "（本轮处理失败：" + attempt.error() + "）" : attempt.text();
        String round = "后台处理 " + batch.size() + " 条输入 · 模型 " + (model == null ? "默认模型" : model) + " · 用时 "
                + String.format("%.1fs", (System.currentTimeMillis() - startedAt) / 1000.0);
        if (attempt.failed()) {
            runLog.error(sa, AgentLogService.SUPERAGENT, "一轮后台处理失败 · " + round, attempt.error());
        } else {
            runLog.info(sa, AgentLogService.SUPERAGENT, "完成一轮后台处理 · " + round, null);
        }
        chats.appendMessage(new ChatMessage(replyId, session.id(), MessageRole.ASSISTANT, reply, Instant.now()));
        String nextConversation = attempt.failed() ? null : attempt.agentSessionId();
        chats.updateSession(new ChatSession(session.id(), session.title(), attempt.type(), nextConversation,
                session.workspaceDir(), null, session.status(), session.createdAt(), Instant.now()));
        synchronized (stateLock) {
            states.find(owner).ifPresent(s -> states.save(s.processedTo(cutoff)));
        }
        log.info("superagent background round for {} handled {} message(s)", owner, batch.size());
    }

    private RoundAttempt attemptRound(String owner, Agent sa, ChatSession session, String model, List<ChatMessage> batch,
                                      String context, String replyId) {
        AgentType type = providers.cliFor(model).map(AgentType::fromString).filter(runners::containsKey)
                .orElse(session.agentType());
        AgentRunner runner = runners.get(type);
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        // Same rule as chat: a stored id only means something to the CLI that minted it.
        String agentSessionId = type == session.agentType() || session.agentSessionId() == null
                ? session.agentSessionId() : session.id();
        AtomicReference<String> captured = new AtomicReference<>(agentSessionId);
        AgentEventSink sink = event -> {
            chats.appendEvent(new AgentEvent(session.id(), replyId, event.seq(), event.type(), event.payload(),
                    event.createdAt()));
            switch (event.type()) {
                case TEXT -> text.append(event.payload());
                case RESULT -> result.set(event.payload());
                case ERROR -> error.set(event.payload());
                case SYSTEM -> {
                    if (event.payload() != null && !event.payload().isBlank()) {
                        captured.set(event.payload());
                    }
                }
                default -> { /* thinking / tool use are kept as events only */ }
            }
        };
        boolean external = external(batch.get(0));
        McpGrants.Grant grant = grants.available() ? grants.openSuper(owner, sa.id(), session.workspaceDir()) : null;
        if (grant != null) {
            backgroundGrants.put(grant.token(), external);
        }
        try {
            if (runner == null) {
                throw new IllegalStateException("No runner for " + type);
            }
            runner.run(new AgentRunRequest(session.id(), agentSessionId, session.workspaceDir(), roundPrompt(owner, batch),
                    model, external ? EXTERNAL_ROUND_TOOLS : BACKGROUND_TOOLS, false, grant == null ? null : grants.mcpConfig(grant),
                    SuperAgentPrompts.background(teamLimit), List.of(), context,
                    credentials == null ? null : credentials.current(type.cliName()).credential()),
                    com.superagent.core.usage.UsageRecorder.tap(usage, new com.superagent.core.usage.UsageContext(owner,
                            com.superagent.core.usage.UsageContext.SOURCE_BACKGROUND, sa.id(), null, session.id(), type.cliName()),
                            sink), new Cancellation());
        } catch (RuntimeException e) {
            log.warn("superagent round attempt failed for {}: {}", owner, e.getMessage());
            error.set(e.getMessage() != null ? e.getMessage() : "round failed");
        } finally {
            if (grant != null) {
                backgroundGrants.remove(grant.token());
            }
            grants.close(grant);
        }
        String out = result.get() != null ? result.get() : text.toString();
        return new RoundAttempt(session, type, out == null ? "" : out, error.get(), captured.get());
    }

    private String roundPrompt(String owner, List<ChatMessage> batch) {
        StringBuilder sb = new StringBuilder("## 新输入（自上一轮以来，按时间顺序）\n");
        for (ChatMessage m : batch) {
            sb.append("- [").append(STAMP.format(m.createdAt().atZone(zone))).append("] ").append(m.content()).append("\n");
        }
        return sb.append("\n").append(statusLines(owner)).toString();
    }

    /** Where things stand, for a round or a chat turn: the plan and the team. */
    String statusLines(String owner) {
        StringBuilder sb = new StringBuilder();
        SuperAgentState s = ensureState(owner); // a new user's first chat comes before any state was kept
        sb.append("## 当前状态\n- 计划：");
        if (s.planId() == null) {
            sb.append("无");
        } else if (SuperAgentState.PLAN_APPROVED.equals(s.planStatus())) {
            sb.append("#").append(s.planId()).append(" 已确认，可以执行");
        } else {
            sb.append("#").append(s.planId()).append(" 已提出，等待用户确认");
        }
        Agent sa = agents.require(s.agentId(), owner);
        sb.append("\n- 团队：").append(team(owner, sa).size()).append("/").append(teamLimit).append(" 人\n");
        return sb.toString();
    }

    /**
     * Follows up the work still open: a task that finished, failed or needs a decision is reported into the
     * inbox (so the next round tells the user) and recorded in the work history, once per change.
     */
    private void reportTrackedTasks(String owner) {
        synchronized (stateLock) {
            SuperAgentState s = states.find(owner).orElse(null);
            if (s == null) {
                return;
            }
            List<SuperAgentWork> open = works.list(owner).stream().filter(SuperAgentWork::open).toList();
            if (open.isEmpty()) {
                return;
            }
            String sessionId = backgroundSession(agents.require(s.agentId(), owner)).id();
            for (SuperAgentWork w : open) {
                Optional<Task> task = tasks.findTask(w.taskId());
                TaskStatus status = task.map(Task::status).orElse(TaskStatus.DELETED);
                // The inbox rules speak task statuses; an open work item has only ever reported a question.
                String reported = SuperAgentWork.NEEDS_CONFIRMATION.equals(w.status())
                        ? TaskStatus.NEEDS_CONFIRMATION.name() : null;
                SuperAgentInbox.Watch watch = SuperAgentInbox.watch(status, reported);
                if (watch == SuperAgentInbox.Watch.KEEP) {
                    continue;
                }
                if (watch != SuperAgentInbox.Watch.DROP) {
                    note(sessionId, report(w, status, task.orElse(null)));
                    logSuper(owner, status == TaskStatus.ERROR ? "warn" : "info", "收到任务结果：「" + w.title() + "」"
                            + status(status) + "（成员 " + w.memberName() + "）", w.taskId());
                }
                boolean finished = watch != SuperAgentInbox.Watch.REPORT_AND_KEEP;
                String summary = outcome(w.taskId(), status, task.orElse(null));
                works.save(w.withStatus(status(status), summary, finished ? Instant.now() : null));
                event(w.taskId(), owner, status(status), summary);
            }
        }
    }

    private static String status(TaskStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** A short snapshot of how a task ended (or what it asks), for the history; the task has the rest. */
    private String outcome(String taskId, TaskStatus status, Task task) {
        String text = switch (status) {
            case DONE, NEEDS_CONFIRMATION -> lastAgentText(taskId);
            case ERROR -> task != null && task.error() != null ? task.error() : "未知错误";
            default -> null;
        };
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= SUMMARY_CHARS ? flat : flat.substring(0, SUMMARY_CHARS) + "…";
    }

    private void event(String taskId, String owner, String kind, String detail) {
        works.appendEvent(new SuperAgentWorkEvent(UUID.randomUUID().toString(), taskId, owner, kind, detail, Instant.now()));
    }

    private String report(SuperAgentWork t, TaskStatus status, Task task) {
        String head = "任务「" + t.title() + "」（" + t.taskId() + "，成员 " + t.memberName() + "）";
        return switch (status) {
            case DONE -> head + "已完成。产出：\n" + clip(lastAgentText(t.taskId()));
            case NEEDS_CONFIRMATION -> head + "需要确认：\n" + clip(lastAgentText(t.taskId()))
                    + "\n（可用 reply_task 回复它，或向用户转述问题）";
            case ERROR -> head + "失败：" + (task != null && task.error() != null ? task.error() : "未知错误");
            case STOPPED -> head + "已被停止。";
            default -> head + "已被删除。";
        };
    }

    private String lastAgentText(String taskId) {
        List<ChatMessage> messages = chats.listMessages(taskId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == MessageRole.ASSISTANT && !messages.get(i).content().isBlank()) {
                return messages.get(i).content();
            }
        }
        return "（无文字产出，请查看任务详情）";
    }

    private static String clip(String s) {
        return s.length() <= REPORT_CHARS ? s : s.substring(0, REPORT_CHARS) + "…（完整内容见任务详情）";
    }

    private void note(String sessionId, String text) {
        chats.appendMessage(new ChatMessage(UUID.randomUUID().toString(), sessionId, MessageRole.SYSTEM, text,
                Instant.now()));
    }

    // --- tool side (called by SuperAgentTools during a round, as the round's owner) ---

    List<TeamMember> team(String owner) {
        return team(owner, agents.ensureSuperAgent(owner));
    }

    /** The user's agents other than the SuperAgent itself: its own hires and the user's own agents alike. */
    private List<TeamMember> team(String owner, Agent sa) {
        Map<String, String> projectOf = new HashMap<>();
        projects(owner).forEach(p -> p.agentIds().forEach(id -> projectOf.put(id, p.name())));
        return agents.owned(owner).stream().filter(a -> !a.isSuper())
                .map(a -> {
                    String parent = agents.parentOf(a.id());
                    return new TeamMember(a.id(), a.name(), AgentCharacters.systemPrompt(a), a.model(),
                            skills.agentSkillIds(a.id()), sa.id().equals(a.managedBy()), projectOf.get(a.id()),
                            parent == null ? null : agents.findName(parent));
                })
                .toList();
    }

    /** The projects the owner is in, active ones first. */
    List<com.superagent.api.project.ProjectDto> projects(String owner) {
        return projects == null ? List.of() : projects.list(owner);
    }

    /**
     * Puts a member into a project and under a parent agent, as the user asked: {@code projectId} / {@code parentId}
     * null leaves that as it is, {@value #NONE} takes it away. Checked before anything changes.
     */
    Agent place(String owner, String memberId, String projectId, String parentId) {
        Agent a = member(owner, memberId);
        checkPlace(owner, a.id(), projectId, parentId);
        applyPlace(owner, a.id(), projectId, parentId);
        String where = describePlace(owner, a.id());
        logSuper(owner, "info", "调整成员「" + a.name() + "」的归属", where);
        return a;
    }

    private void checkPlace(String owner, String agentId, String projectId, String parentId) {
        if (projectId != null && !NONE.equals(projectId)) {
            if (projects == null) {
                throw new IllegalArgumentException("项目功能不可用");
            }
            var p = projects.get(projectId, owner); // not a member: not found
            if (!"active".equals(p.status())) {
                throw new IllegalArgumentException("项目「" + p.name() + "」已归档，不能再放入 Agent");
            }
        }
        if (parentId != null && !NONE.equals(parentId)) {
            Agent above = agents.require(parentId, owner);
            if (above.isSuper()) {
                throw new IllegalArgumentException("你本来就带领所有成员，不用把自己设为上级；不设上级即可（传 none）");
            }
            if (above.id().equals(agentId)) {
                throw new IllegalArgumentException("成员不能做自己的上级");
            }
        }
    }

    private void applyPlace(String owner, String agentId, String projectId, String parentId) {
        if (projectId != null && projects != null) {
            projects.setAgentProject(agentId, owner, NONE.equals(projectId) ? null : projectId);
        }
        if (parentId != null) {
            agents.setParent(agentId, owner, NONE.equals(parentId) ? null : parentId);
        }
    }

    /** "项目 X · 上级 Y" for a member, for the run log and tool replies. */
    String describePlace(String owner, String agentId) {
        String project = projects(owner).stream().filter(p -> p.agentIds().contains(agentId))
                .map(com.superagent.api.project.ProjectDto::name).findFirst().orElse(null);
        String parent = agents.parentOf(agentId);
        return "项目：" + (project == null ? "无" : project) + "；上级：" + (parent == null ? "无（直接由你带领）" : agents.findName(parent));
    }

    /** Records a new plan for the user to approve; any earlier plan is replaced. */
    SuperAgentPlanDto proposePlan(String owner, String summary, List<SuperAgentPlanDto.Member> members,
                                  List<SuperAgentPlanDto.Assignment> assignments) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("A plan needs a summary");
        }
        long newHires = members.stream().filter(m -> m.agentId() == null).count();
        int size = team(owner).size();
        if (size + newHires > teamLimit) {
            throw new IllegalArgumentException("The team would have " + (size + newHires) + " members; the limit is "
                    + teamLimit + ". Reuse existing members instead of hiring.");
        }
        SuperAgentPlanDto plan = new SuperAgentPlanDto(UUID.randomUUID().toString().substring(0, 8),
                SuperAgentState.PLAN_PROPOSED, summary.strip(), List.copyOf(members), List.copyOf(assignments),
                Instant.now());
        synchronized (stateLock) {
            SuperAgentState s = ensureState(owner);
            states.save(s.withPlan(plan.id(), SuperAgentState.PLAN_PROPOSED, toJson(plan)));
            events.publishEvent(new NoticeEvents.PlanChanged(owner, plan.id(), plan.summary(), true));
            logSuper(owner, "info", "提交计划 #" + plan.id() + "（" + members.size() + " 名成员，" + assignments.size()
                    + " 个任务），等待确认", plan.summary());
        }
        return plan;
    }

    Agent hire(String owner, String name, String persona, String avatar, String model, List<String> skillIds) {
        return hire(owner, name, persona, avatar, model, skillIds, null, null);
    }

    /** As above, straight into {@code projectId} and under {@code parentId} (either null: none). */
    Agent hire(String owner, String name, String persona, String avatar, String model, List<String> skillIds,
               String projectId, String parentId) {
        requireApproved(owner);
        Agent sa = agents.ensureSuperAgent(owner);
        if (team(owner, sa).size() >= teamLimit) {
            throw new IllegalArgumentException("The team is full (" + teamLimit + "); reuse or re-brief a member instead");
        }
        requireHealthyModel(model);
        if (team(owner, sa).stream().anyMatch(m -> m.name().equalsIgnoreCase(name == null ? "" : name.strip()))) {
            throw new IllegalArgumentException("A member named " + name + " already exists; re-brief them instead");
        }
        checkPlace(owner, null, projectId, parentId); // before hiring, so a wrong project or parent hires no one
        Agent hired = agents.createTeamMember(owner, sa.id(), name, avatar, persona, model, skillIds);
        applyPlace(owner, hired.id(), projectId, parentId);
        logSuper(owner, "info", "招入成员「" + hired.name() + "」", persona);
        return hired;
    }

    /** Re-briefs a member: only fields given change. */
    Agent adjust(String owner, String memberId, String name, String persona, String model, List<String> skillIds) {
        requireApproved(owner);
        Agent a = member(owner, memberId);
        requireHealthyModel(model);
        agents.update(a.id(), new UpdateAgentRequest(
                name != null ? name : a.name(), persona != null ? persona : a.persona(),
                model != null ? model : a.model(), null, persona != null ? null : a.character(), skillIds), owner);
        logSuper(owner, "info", "调整成员「" + a.name() + "」", persona);
        return member(owner, memberId);
    }

    /** Starts a task run by {@code memberId} under a confirmed plan. */
    String assign(String owner, String memberId, String title, String brief) {
        return assign(owner, memberId, title, brief, null, null);
    }

    /** {@code messageId}: the chat reply that hands it out, for its card there (null from a background round). */
    String assign(String owner, String memberId, String title, String brief, String messageId, String kind) {
        requireApproved(owner);
        return dispatch(owner, member(owner, memberId), title, brief, messageId, kind);
    }

    /** Starts what the user just agreed to in the chat: by a member if one was named, else on its own. */
    String startTask(String owner, String memberId, String title, String brief) {
        return startTask(owner, memberId, title, brief, null, null);
    }

    /** {@code kind}: coding or general (null = general unless it names a repository, see TaskService). */
    String startTask(String owner, String memberId, String title, String brief, String messageId, String kind) {
        return dispatch(owner, memberId == null || memberId.isBlank() ? null : member(owner, memberId), title, brief,
                messageId, kind);
    }

    private String dispatch(String owner, Agent m, String title, String brief, String messageId, String kind) {
        if (title == null || title.isBlank() || brief == null || brief.isBlank()) {
            throw new IllegalArgumentException("A task needs a title and a brief");
        }
        String model = settings.agentDefaultModel(); // a stand-in: the task's model is chosen for it before it runs
        // The member's character and memory ride along with the task itself (TaskDoers), not in the brief.
        String fullBrief = (m == null ? "" : "你是团队成员「" + m.name() + "」，由 SuperAgent 分派本任务。\n\n")
                + "## 任务\n" + brief.strip()
                + "\n\n完成后给出简明的交付总结，说明产出放在哪里。";
        // A member hears about it in its own chat: a short note that it took the task on (the task's card goes under
        // it), and later the result, as for the tasks it sets off itself.
        String memberSession = m == null ? null : chatService.currentSessionId(m.id());
        String noteId = memberSession == null ? null : UUID.randomUUID().toString();
        String taskId = taskService.create(new CreateTaskRequest(title.strip(), fullBrief, "web_chat", null, null, null,
                model, providers.cliFor(model).orElse(null), kind, null), owner, m == null ? null : m.id(),
                memberSession, noteId).id();
        if (noteId != null) {
            chats.appendMessage(new ChatMessage(noteId, memberSession, MessageRole.ASSISTANT,
                    "收到 SuperAgent 分派的任务「" + title.strip() + "」，开始执行。完成后结果会发在这里。",
                    Instant.now(), com.superagent.agent.chat.ChatTaskResults.AUTHOR_PREFIX + taskId));
        }
        // A task with no member is still tracked under the SuperAgent, so its result comes back the same way.
        String doerId = m != null ? m.id() : ensureState(owner).agentId();
        String doer = m != null ? m.name() : "独立任务";
        synchronized (stateLock) {
            Instant now = Instant.now();
            works.save(new SuperAgentWork(taskId, owner, ensureState(owner).planId(), title.strip(), doerId, doer,
                    SuperAgentWork.RUNNING, null, now, null, now, messageId));
            event(taskId, owner, "assigned", "派给 " + doer);
            logSuper(owner, "info", "派出任务「" + title.strip() + "」给「" + doer + "」", "任务 " + taskId);
        }
        log.info("superagent of {} assigned task {} to {}", owner, taskId, doer);
        return taskId;
    }

    /** Answers a task it assigned that is waiting for a decision. */
    void replyToTask(String owner, String taskId, String content) {
        if (works.find(taskId).filter(w -> w.owner().equals(owner)).isEmpty()) {
            throw new IllegalArgumentException("Not a task you assigned: " + taskId);
        }
        taskService.sendMessage(taskId, owner, content);
        works.find(taskId).ifPresent(w -> works.save(w.withStatus(SuperAgentWork.RUNNING, null, null)));
        event(taskId, owner, "replied", content.length() <= SUMMARY_CHARS ? content : content.substring(0, SUMMARY_CHARS) + "…");
    }

    /** Work still open (running or waiting for a decision). */
    List<SuperAgentWork> assigned(String owner) {
        return works.list(owner).stream().filter(SuperAgentWork::open).toList();
    }

    // --- the work history (what the SuperAgent actually got done) ---

    /**
     * The user's SuperAgent work, newest first. {@code status}: all | open | finished | a task status
     * (done / error / stopped / needs_confirmation); {@code memberId} null = everyone; finished work is
     * ordered by when it finished.
     */
    public List<SuperAgentWorkDto> work(String owner, String status, String memberId, int limit) {
        Map<String, String> avatars = new HashMap<>();
        agents.owned(owner).forEach(a -> avatars.put(a.id(), a.avatar()));
        Agent sa = agents.ensureSuperAgent(owner); // independent tasks are tracked under it: they have no member
        boolean finishedOnly = "finished".equals(status);
        return works.list(owner).stream()
                .filter(w -> status == null || status.isBlank() || "all".equals(status)
                        || ("open".equals(status) ? w.open() : finishedOnly ? !w.open() : status.equals(w.status())))
                .filter(w -> memberId == null || memberId.isBlank() || memberId.equals(w.memberId()))
                .sorted(finishedOnly
                        ? Comparator.comparing(SuperAgentWork::finishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        : Comparator.comparing(SuperAgentWork::assignedAt, Comparator.reverseOrder()))
                .limit(Math.max(1, Math.min(limit, 500)))
                .map(w -> new SuperAgentWorkDto(w.taskId(), w.planId(), w.title(), w.memberId(), w.memberName(),
                        avatars.get(w.memberId()), w.status(), w.summary(), w.assignedAt(), w.finishedAt(), w.messageId(),
                        w.memberId() != null && !w.memberId().equals(sa.id())))
                .toList();
    }

    public List<SuperAgentWorkEventDto> workEvents(String owner, String taskId) {
        works.find(taskId).filter(w -> w.owner().equals(owner))
                .orElseThrow(() -> new IllegalArgumentException("No such work: " + taskId));
        return works.events(taskId).stream().map(e -> new SuperAgentWorkEventDto(e.kind(), e.detail(), e.at())).toList();
    }

    /**
     * Work assigned before the history existed has no record; rebuild it from the tasks themselves (their brief
     * names the member). Plans and in-between events weren't kept, so those stay empty. Safe to repeat.
     */
    @EventListener(CompanyStarted.class)
    void backfillWork() {
        int added = 0;
        for (Task t : tasks.listTasks()) {
            Matcher m = t.brief() == null ? null : ASSIGNED_BRIEF.matcher(t.brief());
            if (m == null || !m.find() || t.status() == TaskStatus.DELETED || works.find(t.id()).isPresent()) {
                continue;
            }
            String member = m.group(1);
            String memberId = agents.owned(t.createdBy()).stream().filter(a -> a.name().equals(member))
                    .map(Agent::id).findFirst().orElse(null);
            boolean open = t.status() == TaskStatus.PENDING || t.status() == TaskStatus.RUNNING
                    || t.status() == TaskStatus.NEEDS_CONFIRMATION;
            String status = open ? SuperAgentWork.RUNNING : status(t.status());
            Instant finished = open ? null : (t.finishedAt() != null ? t.finishedAt() : t.updatedAt());
            works.save(new SuperAgentWork(t.id(), t.createdBy(), null, t.title(), memberId, member, status,
                    open ? null : outcome(t.id(), t.status(), t), t.createdAt(), finished, Instant.now()));
            works.appendEvent(new SuperAgentWorkEvent(UUID.randomUUID().toString(), t.id(), t.createdBy(), "assigned",
                    "派给 " + member + "（补录）", t.createdAt()));
            if (!open) {
                works.appendEvent(new SuperAgentWorkEvent(UUID.randomUUID().toString(), t.id(), t.createdBy(), status,
                        "（补录）", finished));
            }
            added++;
        }
        if (added > 0) {
            log.info("back-filled {} SuperAgent work record(s) from existing tasks", added);
        }
    }

    Optional<Task> task(String owner, String taskId) {
        return tasks.findTask(taskId).filter(t -> Objects.equals(t.createdBy(), owner));
    }

    /** The grant belongs to a background round (not the chat). */
    boolean isBackground(McpGrants.Grant grant) {
        return backgroundGrants.containsKey(grant.token());
    }

    /** Invitations the SuperAgent proposed in its chat, for their cards there. */
    public List<com.superagent.api.agent.AgentInviteDto> invites(String owner) {
        Agent sa = agents.require(ensureState(owner).agentId(), owner);
        Set<String> sessions = chats.listSessionsByAgent(sa.id()).stream().map(ChatSession::id).collect(Collectors.toSet());
        return inviteService.proposedIn(owner, sessions);
    }

    /** Refuses the team-changing tools in a background round that works received messages. */
    void requireOwnInput(McpGrants.Grant grant) {
        if (Boolean.TRUE.equals(backgroundGrants.get(grant.token()))) {
            throw new IllegalArgumentException("处理外部消息时不能招人、调整成员、派任务或回复任务；需要团队去做的事请用 propose_plan 提计划。");
        }
    }

    /**
     * The background tells the user something in the chat: marked as coming from the background, and seen by the
     * chat's next turn. Only background rounds may: the chat answers the user directly.
     */
    void tellUser(McpGrants.Grant grant, String text) {
        if (!backgroundGrants.containsKey(grant.token())) {
            throw new IllegalArgumentException("tell_user is for background rounds; in the chat, just answer the user");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Nothing to tell");
        }
        Agent sa = agents.ensureSuperAgent(grant.owner());
        chats.appendMessage(new ChatMessage(UUID.randomUUID().toString(), sessionId(sa), MessageRole.ASSISTANT,
                text.strip(), Instant.now(), BACKGROUND_AUTHOR));
        logSuper(grant.owner(), "info", "后台告知用户", text.strip());
    }

    /**
     * What the background posted into the chat since the chat last answered, oldest first: the chat's own CLI
     * conversation never saw these, so its next turn is told.
     */
    List<String> backgroundPostsSinceLastReply(String sessionId) {
        List<ChatMessage> messages = chats.listMessages(sessionId);
        List<String> posts = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.role() != MessageRole.ASSISTANT) {
                continue;
            }
            if (!BACKGROUND_AUTHOR.equals(m.author())) {
                break;
            }
            posts.add(0, m.content());
        }
        return posts;
    }

    /** The background record: what reached the queue and how each round answered, newest last. */
    public List<com.superagent.api.chat.MessageDto> background(String owner, int limit) {
        SuperAgentState s = ensureState(owner);
        List<ChatMessage> all = chats.listMessages(backgroundSession(agents.require(s.agentId(), owner)).id());
        return all.subList(Math.max(0, all.size() - Math.max(1, limit)), all.size()).stream()
                .map(m -> new com.superagent.api.chat.MessageDto(m.id(), m.role().name().toLowerCase(Locale.ROOT),
                        m.content(), m.createdAt(), List.of(), null, null))
                .toList();
    }

    private void requireApproved(String owner) {
        SuperAgentState s = ensureState(owner);
        if (!SuperAgentState.PLAN_APPROVED.equals(s.planStatus())) {
            throw new IllegalArgumentException("计划尚未确认：请先用 propose_plan 提交计划，并请用户在界面上点击「确认执行」。"
                    + "确认之前不能新建、调整成员或派任务。");
        }
    }

    private void requireHealthyModel(String model) {
        if (model != null && !model.isBlank() && providers.cliFor(model.strip()).isEmpty()) {
            throw new IllegalArgumentException("Model " + model + " has not passed the inference check");
        }
    }

    /** One of the user's agents other than the SuperAgent. */
    private Agent member(String owner, String id) {
        Agent a = agents.require(id, owner);
        if (a.isSuper()) {
            throw new IllegalArgumentException("SuperAgent itself isn't a team member");
        }
        return a;
    }

    // --- helpers ---

    /** Writes a line to the owner's SuperAgent run log (the tool methods only know the owner). */
    private void logSuper(String owner, String level, String summary, String detail) {
        agents.findSuperAgent(owner).ifPresent(sa -> {
            switch (level) {
                case "warn" -> runLog.warn(sa, AgentLogService.SUPERAGENT, summary, detail);
                case "error" -> runLog.error(sa, AgentLogService.SUPERAGENT, summary, detail);
                default -> runLog.info(sa, AgentLogService.SUPERAGENT, summary, detail);
            }
        });
    }

    /** The SuperAgent's current chat session. */
    private String sessionId(Agent sa) {
        return chatService.currentSessionId(sa.id());
    }

    /**
     * The background's own session: its queue (system notes, received messages) and its rounds' replies, with a CLI
     * conversation of its own. It belongs to no agent, so it never shows in the chat; its id follows from the
     * SuperAgent's.
     */
    private ChatSession backgroundSession(Agent sa) {
        String id = UUID.nameUUIDFromBytes(("superagent-background:" + sa.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return chatService.ensureDetachedSession(id, "SuperAgent 后台");
    }

    private SuperAgentPlanDto plan(SuperAgentState s) {
        if (s.planJson() == null) {
            return null;
        }
        try {
            SuperAgentPlanDto p = json.readValue(s.planJson(), SuperAgentPlanDto.class);
            return new SuperAgentPlanDto(p.id(), s.planStatus(), p.summary(), p.members(), p.tasks(), p.proposedAt());
        } catch (JsonProcessingException e) {
            return null; // unreadable plan: as if none; the next proposal replaces it
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize superagent state", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
