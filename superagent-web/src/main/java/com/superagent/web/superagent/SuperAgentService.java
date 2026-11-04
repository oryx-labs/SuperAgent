package com.superagent.web.superagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.ImageUploadDto;
import com.superagent.api.superagent.SuperAgentPlanDto;
import com.superagent.api.superagent.SuperAgentStateDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.agent.SuperAgentState;
import com.superagent.core.agent.SuperAgentStateStore;
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
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import com.superagent.web.agent.AgentService;
import com.superagent.web.attachment.ImageAttachmentStore;
import com.superagent.web.chat.ChatService;
import com.superagent.web.memory.AgentMemoryService;
import com.superagent.web.mcp.McpGrants;
import com.superagent.web.provider.ProviderService;
import com.superagent.web.settings.SettingsService;
import com.superagent.web.skill.SkillService;
import com.superagent.web.task.TaskService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
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
 */
@Service
public class SuperAgentService {

    private static final Logger log = LoggerFactory.getLogger(SuperAgentService.class);
    private static final TypeReference<List<Tracked>> TRACKED = new TypeReference<>() { };
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final int REPORT_CHARS = 1500;

    /** Tool names as the CLI sees them; a round may call these and nothing else. */
    static final List<String> ROUND_TOOLS = SuperAgentTools.NAMES.stream().map(n -> "mcp__superagent__" + n).toList();

    /** A task the SuperAgent assigned; {@code reported} is the last status it told the user about. */
    record Tracked(String taskId, String memberId, String memberName, String title, String reported) {
        Tracked reportedAs(String status) {
            return new Tracked(taskId, memberId, memberName, title, status);
        }
    }

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
    private final SkillService skills;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final Duration quiet;
    private final Duration maxWait;
    private final int teamLimit;
    private final ZoneId zone;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Object stateLock = new Object();
    /** owner → cutoff of the round running for them. */
    private final Map<String, Instant> running = new ConcurrentHashMap<>();
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
                             ChatService chatService, AgentMemoryService memory,
                             List<AgentRunner> agentRunners,
                             @Value("${superagent.superagent.quiet-seconds:10}") long quietSeconds,
                             @Value("${superagent.superagent.max-wait-seconds:60}") long maxWaitSeconds,
                             @Value("${superagent.superagent.team-limit:6}") int teamLimit,
                             @Value("${superagent.superagent.timezone:Asia/Shanghai}") String timezone) {
        this.agents = agents;
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

    /** The user approves the latest plan: the next round may hire and assign, and starts right away. */
    public SuperAgentStateDto approvePlan(String owner, String planId) {
        synchronized (stateLock) {
            SuperAgentState s = ensureState(owner);
            if (!Objects.equals(s.planId(), planId) || !SuperAgentState.PLAN_PROPOSED.equals(s.planStatus())) {
                throw new IllegalArgumentException("This plan is no longer waiting for approval");
            }
            states.save(s.withPlan(s.planId(), SuperAgentState.PLAN_APPROVED, s.planJson()));
            note(sessionId(agents.require(s.agentId(), owner)), "用户已确认计划，请开始执行。");
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
        List<ChatMessage> pending = SuperAgentInbox.pending(agentMessages(sa.id()), s.processedUntil());
        if (!SuperAgentInbox.due(pending, now, quiet, maxWait, immediate.contains(owner))) {
            return;
        }
        immediate.remove(owner);
        Instant last = pending.get(pending.size() - 1).createdAt();
        if (running.putIfAbsent(owner, last) != null) {
            return;
        }
        executor.execute(() -> {
            try {
                runRound(owner, sa, pending, last);
            } finally {
                running.remove(owner);
            }
        });
    }

    private void runRound(String owner, Agent sa, List<ChatMessage> batch, Instant cutoff) {
        String model = sa.model() != null ? sa.model() : settings.agentDefaultModel();
        // Like any agent: a new model means a new session, which starts from the SuperAgent's memory.
        ChatSession session = chatService.sessionForTurn(sa, model);
        String sessionId = session.id();
        AgentType type = providers.cliFor(model).map(AgentType::fromString).filter(runners::containsKey)
                .orElse(session.agentType());
        AgentRunner runner = runners.get(type);
        String replyId = UUID.randomUUID().toString();
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        // Same rule as chat: a stored id only means something to the CLI that minted it.
        String agentSessionId = type == session.agentType() || session.agentSessionId() == null
                ? session.agentSessionId() : session.id();
        AtomicReference<String> captured = new AtomicReference<>(agentSessionId);
        AgentEventSink sink = event -> {
            chats.appendEvent(new AgentEvent(sessionId, replyId, event.seq(), event.type(), event.payload(),
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
        String reply;
        try {
            if (runner == null) {
                throw new IllegalStateException("No runner for " + type);
            }
            runner.run(new AgentRunRequest(session.id(), agentSessionId, session.workspaceDir(), roundPrompt(owner, batch),
                    model, ROUND_TOOLS, false, grant == null ? null : grants.mcpConfig(grant),
                    SuperAgentPrompts.system(teamLimit), attached, memory.read(sa)), sink, new Cancellation());
            String out = result.get() != null ? result.get() : text.toString();
            reply = out.isBlank() && error.get() != null ? "（本轮处理失败：" + error.get() + "）" : out;
        } catch (RuntimeException e) {
            // The round's outcome is shown to the user in the conversation; the cursor still moves on, so a
            // broken batch can't be retried forever.
            log.warn("superagent round failed for {}", owner, e);
            reply = "（本轮处理失败：" + e.getMessage() + "）";
        } finally {
            grants.close(grant);
        }
        chats.appendMessage(new ChatMessage(replyId, sessionId, MessageRole.ASSISTANT, reply, Instant.now()));
        ChatSession updated = type != session.agentType()
                ? new ChatSession(session.id(), session.title(), type, captured.get(), session.workspaceDir(),
                session.agentId(), session.status(), session.createdAt(), Instant.now())
                : session.withAgentSessionId(captured.get());
        chats.updateSession(updated);
        synchronized (stateLock) {
            states.find(owner).ifPresent(s -> states.save(s.processedTo(cutoff)));
        }
        log.info("superagent round for {} handled {} message(s)", owner, batch.size());
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

    private void reportTrackedTasks(String owner) {
        synchronized (stateLock) {
            SuperAgentState s = states.find(owner).orElse(null);
            if (s == null) {
                return;
            }
            List<Tracked> tracked = tracked(s);
            if (tracked.isEmpty()) {
                return;
            }
            String sessionId = sessionId(agents.require(s.agentId(), owner));
            List<Tracked> next = new ArrayList<>();
            boolean changed = false;
            for (Tracked t : tracked) {
                Optional<Task> task = tasks.findTask(t.taskId());
                TaskStatus status = task.map(Task::status).orElse(TaskStatus.DELETED);
                switch (SuperAgentInbox.watch(status, t.reported())) {
                    case KEEP -> next.add(t);
                    case REPORT_AND_KEEP -> {
                        note(sessionId, report(t, status, task.orElse(null)));
                        next.add(t.reportedAs(status.name()));
                        changed = true;
                    }
                    case REPORT_AND_DROP -> {
                        note(sessionId, report(t, status, task.orElse(null)));
                        changed = true;
                    }
                    case DROP -> changed = true;
                }
            }
            if (changed) {
                states.save(s.withTracked(toJson(next)));
            }
        }
    }

    private String report(Tracked t, TaskStatus status, Task task) {
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
        return agents.createTeamMember(owner, sa.id(), name, avatar, persona, model, skillIds);
    }

    /** Re-briefs a member: only fields given change. */
    Agent adjust(String owner, String memberId, String name, String persona, String model, List<String> skillIds) {
        requireApproved(owner);
        Agent a = member(owner, memberId);
        requireHealthyModel(model);
        agents.update(a.id(), new UpdateAgentRequest(
                name != null ? name : a.name(), persona != null ? persona : a.persona(),
                model != null ? model : a.model(), null, persona != null ? null : a.character(), skillIds), owner);
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
            SuperAgentState s = ensureState(owner);
            List<Tracked> tracked = new ArrayList<>(tracked(s));
            tracked.add(new Tracked(taskId, m.id(), m.name(), title.strip(), null));
            states.save(s.withTracked(toJson(tracked)));
        }
        log.info("superagent of {} assigned task {} to {}", owner, taskId, m.name());
        return taskId;
    }

    /** Answers a tracked task that is waiting for a decision. */
    void replyToTask(String owner, String taskId, String content) {
        SuperAgentState s = ensureState(owner);
        if (tracked(s).stream().noneMatch(t -> t.taskId().equals(taskId))) {
            throw new IllegalArgumentException("Not a task you assigned: " + taskId);
        }
        taskService.sendMessage(taskId, content);
    }

    List<Tracked> assigned(String owner) {
        return tracked(ensureState(owner));
    }

    Optional<Task> task(String owner, String taskId) {
        return tasks.findTask(taskId).filter(t -> Objects.equals(t.createdBy(), owner));
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

    private List<Tracked> tracked(SuperAgentState s) {
        if (s.trackedJson() == null) {
            return List.of();
        }
        try {
            return json.readValue(s.trackedJson(), TRACKED);
        } catch (JsonProcessingException e) {
            return List.of();
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
