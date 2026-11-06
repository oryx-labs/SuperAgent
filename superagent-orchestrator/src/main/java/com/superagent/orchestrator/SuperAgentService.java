package com.superagent.orchestrator;

import com.superagent.platform.provider.CredentialService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.superagent.agent.AgentService;
import com.superagent.agent.chat.ChatService;
import com.superagent.agent.log.AgentLogService;
import com.superagent.agent.memory.AgentMemoryService;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.ImageUploadDto;
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
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.knowledge.skill.SkillService;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import com.superagent.runtime.attachment.ImageAttachmentStore;
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
 * Each user's SuperAgent: a leader that takes requests through an inbox instead of answering live.
 *
 * <p><b>Inbox and rounds.</b> Messages land in the SuperAgent's chat session at once. The loop
 * ({@link SuperAgentLoop}) checks every few seconds; once new input has gone quiet for
 * {@code quiet} (or has waited {@code max-wait}, so a steady stream still gets answered), everything
 * since the last round is handed to the agent as one batch. One round at a time per user; anything
 * arriving meanwhile goes to the next round. {@link SuperAgentState#processedUntil()} is the cursor.
 *
 * <p><b>Plan first.</b> The agent proposes a plan (who on the team does what). Hiring, re-briefing
 * members and assigning tasks are refused until the user approves that plan in the UI — enforced
 * here, not just asked of the model.
 *
 * <p><b>Closing the loop.</b> Tasks it assigns are tracked; when one finishes, fails, or needs a
 * decision, a system message lands in the inbox and the next round reports it to the user.
 *
 * <p><b>The outside world.</b> Messages the user's data sources receive (Feishu, ...) are delivered here too
 * ({@link #deliverExternal}), marked as third-party data. They're worked in rounds of their own, with only
 * the inbox and read-only tools: text from strangers never runs in a round that may hire, assign or reply.
 */
@Service
public class SuperAgentService {

    private static final Logger log = LoggerFactory.getLogger(SuperAgentService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final int REPORT_CHARS = 1500;

    /** Tool names as the CLI sees them; a round may call these and nothing else. */
    static final List<String> ROUND_TOOLS = Stream.concat(
            SuperAgentTools.NAMES.stream().map(n -> "mcp__superagent__" + n), McpGrants.INBOX_TOOLS.stream()).toList();

    /** Marks a delivery of received messages; such rounds can't touch the team or its tasks. */
    static final String EXTERNAL = "【外部消息】";
    private static final Set<String> ACTING_TOOLS = Set.of("hire_member", "update_member", "assign_task", "reply_task");
    static final List<String> EXTERNAL_ROUND_TOOLS = ROUND_TOOLS.stream()
            .filter(t -> !ACTING_TOOLS.contains(t.substring("mcp__superagent__".length()))).toList();

    /** A task the SuperAgent assigned; {@code reported} is the last status it told the user about. */
    /** Recognizes a task the SuperAgent assigned (its brief opens with this), to back-fill the work record. */
    private static final Pattern ASSIGNED_BRIEF = Pattern.compile("^你是团队成员「(.+?)」，由 SuperAgent 分派本任务");
    private static final int SUMMARY_CHARS = 300;

    /** A member as the tools describe it. */
    record TeamMember(String id, String name, String persona, String model, List<String> skillIds, boolean hired) {
    }

    private final AgentService agents;
    private final SuperAgentStateStore states;
    private final ChatStore chats;
    private final TaskService taskService;
    private final TaskStore tasks;
    private final ProviderService providers;
    private final SettingsService settings;
    private final ImageAttachmentStore images;
    private final McpGrants grants;
    private final ChatService chatService;
    private final AgentMemoryService memory;
    private final SuperAgentWorkStore works;
    private final AgentLogService runLog;
    private final ApplicationEventPublisher events;
    private final SkillService skills;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);

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
    /** owner → cutoff of the round running for them. */
    private final Map<String, Instant> running = new ConcurrentHashMap<>();
    /** Owners whose current round works received messages: it may not touch the team or its tasks. */
    private final Set<String> externalRound = ConcurrentHashMap.newKeySet();
    /** Owners whose next round should start without waiting for quiet (they just approved a plan). */
    private final Set<String> immediate = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "superagent-round");
        t.setDaemon(true);
        return t;
    });

    public SuperAgentService(AgentService agents, SuperAgentStateStore states, ChatStore chats, TaskService taskService,
                             TaskStore tasks, ProviderService providers, SettingsService settings,
                             ImageAttachmentStore images, McpGrants grants, SkillService skills,
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
        this.images = images;
        this.grants = grants;
        this.chatService = chatService;
        this.memory = memory;
        this.works = works;
        this.runLog = runLog;
        this.skills = skills;
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

    /** Drops a message into the inbox; it is worked through in the next round. */
    public SuperAgentStateDto submit(String owner, String content, List<ImageUploadDto> uploads) {
        SuperAgentState s = ensureState(owner);
        String sessionId = sessionId(agents.require(s.agentId(), owner));
        String id = UUID.randomUUID().toString();
        List<ImageAttachment> attached = images.save(sessionId, id, uploads);
        String text = content == null ? "" : content.strip();
        if (text.isEmpty() && attached.isEmpty()) {
            throw new IllegalArgumentException("Message is empty");
        }
        chats.appendMessage(new ChatMessage(id, sessionId, MessageRole.USER, text, Instant.now()));
        return state(owner);
    }

    /**
     * Received messages for the owner's SuperAgent; an urgent delivery starts a round right away. The text says
     * what kind of batch it is and lists the messages as quoted data.
     */
    public void deliverExternal(String owner, String text, boolean urgent) {
        SuperAgentState s = ensureState(owner);
        note(sessionId(agents.require(s.agentId(), owner)), EXTERNAL + text);
        if (urgent) {
            immediate.add(owner);
        }
    }

    static boolean external(ChatMessage m) {
        return m.role() == MessageRole.SYSTEM && m.content().startsWith(EXTERNAL);
    }

    /** The user approves the latest plan: the next round may hire and assign, and starts right away. */
    public SuperAgentStateDto approvePlan(String owner, String planId) {
        synchronized (stateLock) {
            SuperAgentState s = ensureState(owner);
            if (!Objects.equals(s.planId(), planId) || !SuperAgentState.PLAN_PROPOSED.equals(s.planStatus())) {
                throw new IllegalArgumentException("This plan is no longer waiting for approval");
            }
            states.save(s.withPlan(s.planId(), SuperAgentState.PLAN_APPROVED, s.planJson()));
            events.publishEvent(new NoticeEvents.PlanChanged(owner, planId, null, false));
            note(sessionId(agents.require(s.agentId(), owner)), "用户已确认计划，请开始执行。");
            logSuper(owner, "info", "你确认了计划 #" + planId + "，开始执行", null);
            immediate.add(owner);
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
        String sessionId = sa == null ? null : sessionId(sa);
        if (sessionId == null) {
            return;
        }
        List<ChatMessage> all = SuperAgentInbox.pending(agentMessages(sa.id()), s.processedUntil());
        if (!SuperAgentInbox.due(all, now, quiet, maxWait, immediate.contains(owner))) {
            return;
        }
        immediate.remove(owner);
        // Received messages and the user's own input never share a round (see the class comment).
        boolean external = external(all.get(0));
        List<ChatMessage> pending = all.stream().takeWhile(m -> external(m) == external).toList();
        if (pending.size() < all.size()) {
            immediate.add(owner); // the rest is due already: take it next tick
        }
        Instant last = pending.get(pending.size() - 1).createdAt();
        if (running.putIfAbsent(owner, last) != null) {
            return;
        }
        executor.execute(() -> {
            if (external) {
                externalRound.add(owner);
            }
            try {
                runRound(owner, sa, pending, last);
            } finally {
                externalRound.remove(owner);
                running.remove(owner);
            }
        });
    }

    /** How one attempt at a round went; {@code error} set + blank {@code text} = it failed. */
    private record RoundAttempt(ChatSession session, AgentType type, String text, String error, String agentSessionId) {
        boolean failed() {
            return text.isBlank() && error != null;
        }
    }

    /**
     * Works one batch. Like any agent's turn: if it fails and that tips the session over its failure limit
     * (network trouble never counts), the SuperAgent moves to a fresh session and tries the batch once more.
     * The reply — or why the round failed — goes into the conversation either way, and the cursor moves on,
     * so a broken batch can't be retried forever.
     */
    private void runRound(String owner, Agent sa, List<ChatMessage> batch, Instant cutoff) {
        long startedAt = System.currentTimeMillis();
        String model = sa.model() != null ? sa.model() : settings.agentDefaultModel();
        // Like any agent: a new model means a new session, which starts from the SuperAgent's memory.
        ChatSession session = chatService.sessionForTurn(sa, model);
        String replyId = UUID.randomUUID().toString();
        // The batch is this round's input already; the "recent conversation" is what came before it.
        Set<String> batchIds = batch.stream().map(ChatMessage::id).collect(Collectors.toSet());
        String context = session.agentSessionId() == null ? memory.context(sa, batchIds) : memory.read(sa);
        RoundAttempt attempt = attemptRound(owner, sa, session, model, batch, context, replyId);
        if (!attempt.failed()) {
            memory.recordSuccess(sa);
        } else if (memory.recordFailure(sa, attempt.error())) {
            attempt = attemptRound(owner, sa, chatService.rotateSession(sa), model, batch,
                    memory.context(sa, batchIds), replyId);
            if (attempt.failed()) {
                memory.recordFailure(sa, attempt.error());
            } else {
                memory.recordSuccess(sa);
            }
        }
        String reply = attempt.failed() ? "（本轮处理失败：" + attempt.error() + "）" : attempt.text();
        String round = "处理 " + batch.size() + " 条输入 · 模型 " + (model == null ? "默认模型" : model) + " · 用时 "
                + String.format("%.1fs", (System.currentTimeMillis() - startedAt) / 1000.0);
        if (attempt.failed()) {
            runLog.error(sa, AgentLogService.SUPERAGENT, "一轮处理失败 · " + round, attempt.error());
        } else {
            runLog.info(sa, AgentLogService.SUPERAGENT, "完成一轮 · " + round, null);
        }
        ChatSession used = attempt.session();
        chats.appendMessage(new ChatMessage(replyId, used.id(), MessageRole.ASSISTANT, reply, Instant.now()));
        chats.updateSession(attempt.type() != used.agentType()
                ? new ChatSession(used.id(), used.title(), attempt.type(), attempt.agentSessionId(), used.workspaceDir(),
                used.agentId(), used.status(), used.createdAt(), Instant.now())
                : used.withAgentSessionId(attempt.agentSessionId()));
        synchronized (stateLock) {
            states.find(owner).ifPresent(s -> states.save(s.processedTo(cutoff)));
        }
        log.info("superagent round for {} handled {} message(s)", owner, batch.size());
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
        List<ImageAttachment> attached = new ArrayList<>();
        batch.stream().filter(m -> m.role() == MessageRole.USER)
                .forEach(m -> attached.addAll(images.list(m.sessionId(), m.id())));
        McpGrants.Grant grant = grants.available() ? grants.openSuper(owner, sa.id(), session.workspaceDir()) : null;
        try {
            if (runner == null) {
                throw new IllegalStateException("No runner for " + type);
            }
            runner.run(new AgentRunRequest(session.id(), agentSessionId, session.workspaceDir(), roundPrompt(owner, batch),
                    model, external(batch.get(0)) ? EXTERNAL_ROUND_TOOLS : ROUND_TOOLS, false, grant == null ? null : grants.mcpConfig(grant),
                    SuperAgentPrompts.system(teamLimit), attached, context,
                    credentials == null ? null : credentials.current(type.cliName()).credential()), sink, new Cancellation());
        } catch (RuntimeException e) {
            log.warn("superagent round attempt failed for {}: {}", owner, e.getMessage());
            error.set(e.getMessage() != null ? e.getMessage() : "round failed");
        } finally {
            grants.close(grant);
        }
        String out = result.get() != null ? result.get() : text.toString();
        return new RoundAttempt(session, type, out == null ? "" : out, error.get(), captured.get());
    }

    private String roundPrompt(String owner, List<ChatMessage> batch) {
        StringBuilder sb = new StringBuilder("## 新输入（自上一轮以来，按时间顺序）\n");
        for (ChatMessage m : batch) {
            sb.append("- [").append(STAMP.format(m.createdAt().atZone(zone))).append("] ")
                    .append(m.role() == MessageRole.USER ? "用户：" : "系统：")
                    .append(m.content().isBlank() ? "（见附图）" : m.content()).append("\n");
        }
        SuperAgentState s = states.find(owner).orElseThrow();
        sb.append("\n## 当前状态\n- 计划：");
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
            String sessionId = sessionId(agents.require(s.agentId(), owner));
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
        return agents.owned(owner).stream().filter(a -> !a.isSuper())
                .map(a -> new TeamMember(a.id(), a.name(), AgentCharacters.systemPrompt(a), a.model(),
                        skills.agentSkillIds(a.id()), sa.id().equals(a.managedBy())))
                .toList();
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
        requireApproved(owner);
        Agent sa = agents.ensureSuperAgent(owner);
        if (team(owner, sa).size() >= teamLimit) {
            throw new IllegalArgumentException("The team is full (" + teamLimit + "); reuse or re-brief a member instead");
        }
        requireHealthyModel(model);
        if (team(owner, sa).stream().anyMatch(m -> m.name().equalsIgnoreCase(name == null ? "" : name.strip()))) {
            throw new IllegalArgumentException("A member named " + name + " already exists; re-brief them instead");
        }
        Agent hired = agents.createTeamMember(owner, sa.id(), name, avatar, persona, model, skillIds);
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

    /** Starts a task run by {@code memberId} (their character leads the brief; their skills and model apply). */
    String assign(String owner, String memberId, String title, String brief) {
        requireApproved(owner);
        if (title == null || title.isBlank() || brief == null || brief.isBlank()) {
            throw new IllegalArgumentException("A task needs a title and a brief");
        }
        Agent m = member(owner, memberId);
        String model = m.model() != null ? m.model() : settings.agentDefaultModel();
        String character = AgentCharacters.systemPrompt(m);
        String fullBrief = "你是团队成员「" + m.name() + "」，由 SuperAgent 分派本任务。"
                + (character == null ? "" : "\n\n## 你的角色\n" + character)
                + "\n\n## 任务\n" + brief.strip()
                + "\n\n完成后给出简明的交付总结，说明产出放在哪里。";
        String taskId = taskService.create(new CreateTaskRequest(title.strip(), fullBrief, "web_chat", null, null, null,
                model, providers.cliFor(model).orElse(null), null), owner, m.id()).id();
        synchronized (stateLock) {
            Instant now = Instant.now();
            works.save(new SuperAgentWork(taskId, owner, ensureState(owner).planId(), title.strip(), m.id(), m.name(),
                    SuperAgentWork.RUNNING, null, now, null, now));
            event(taskId, owner, "assigned", "派给 " + m.name());
            logSuper(owner, "info", "派出任务「" + title.strip() + "」给「" + m.name() + "」", "任务 " + taskId);
        }
        log.info("superagent of {} assigned task {} to {}", owner, taskId, m.name());
        return taskId;
    }

    /** Answers a task it assigned that is waiting for a decision. */
    void replyToTask(String owner, String taskId, String content) {
        requireOwnInput(owner);
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
                        avatars.get(w.memberId()), w.status(), w.summary(), w.assignedAt(), w.finishedAt()))
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
    @EventListener(ApplicationReadyEvent.class)
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

    private void requireOwnInput(String owner) {
        if (externalRound.contains(owner)) {
            throw new IllegalArgumentException("处理外部消息时不能招人、调整成员、派任务或回复任务；需要团队去做的事请用 propose_plan 提计划。");
        }
    }

    private void requireApproved(String owner) {
        requireOwnInput(owner);
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

    /** Where new inbox messages go: the SuperAgent's current session. */
    private String sessionId(Agent sa) {
        return chatService.currentSessionId(sa.id());
    }

    /** The inbox spans every session the SuperAgent has had, oldest first. */
    private List<ChatMessage> agentMessages(String agentId) {
        return chats.listSessionsByAgent(agentId).stream()
                .filter(x -> x.status() != SessionStatus.DELETED)
                .flatMap(x -> chats.listMessages(x.id()).stream())
                .sorted(Comparator.comparing(ChatMessage::createdAt))
                .toList();
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
