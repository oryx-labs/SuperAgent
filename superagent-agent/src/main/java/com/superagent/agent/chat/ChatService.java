package com.superagent.agent.chat;

import com.superagent.agent.AgentCollabMcpTools;
import com.superagent.agent.log.AgentLogService;
import com.superagent.agent.memory.AgentMemoryService;
import com.superagent.api.ApiPaths;
import com.superagent.api.chat.ChatSegmentDto;
import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.ImageUploadDto;
import com.superagent.api.chat.LiveTurnDto;
import com.superagent.api.chat.MessageDto;
import com.superagent.api.chat.SessionDetailDto;
import com.superagent.api.chat.SessionDto;
import com.superagent.api.chat.StreamEventDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.agent.AgentMemberStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
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
import com.superagent.core.chat.ImageAttachment;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.tenant.TenantPaths;
import com.superagent.core.user.UserStore;
import com.superagent.files.FileService;
import com.superagent.platform.tenant.QuotaService;
import com.superagent.skills.SkillService;
import com.superagent.platform.provider.CredentialService;
import com.superagent.platform.provider.EndpointFailures;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import com.superagent.runtime.attachment.ImageAttachmentStore;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.runtime.mcp.McpGrants;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates a chat turn: persist the user message, drive the agent, stream
 * every event to the client over SSE, and persist the settled reply.
 *
 * <p>Depends only on the {@link ChatStore} and {@link AgentRunner} SPIs from
 * {@code superagent-core}; the concrete storage backend and CLI runners are
 * contributed by {@code superagent-storage} and {@code superagent-worker} at
 * runtime, so this module stays independent of them.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatStore store;
    private final AgentStore agentStore;
    private final AgentMemberStore members;
    private final UserStore users;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final Map<String, Cancellation> activeRuns = new ConcurrentHashMap<>();
    /** The turn in flight per agent (or per session, for agent-less chats) — one at a time, visible to every member. */
    private final Map<String, LiveTurn> liveTurns = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-turn");
        t.setDaemon(true);
        return t;
    });

    private final Path workspaceRoot;
    private final SettingsService settingsService;
    private final ProviderService providerService;
    private CredentialService credentials; // null in tests: runs use the server's environment
    private final ImageAttachmentStore images;
    private final FileService files;
    private final String defaultModel;
    private final List<String> allowedTools;
    private final boolean skipPermissions;
    private final long sseTimeoutMs;
    private final McpGrants mcpGrants;   // null = chat agents get no SuperAgent MCP
    private final SkillService skills;
    private final AgentMemoryService memory;
    private final AgentLogService runLog;
    private ChatTaskResults taskResults; // null in tests: no task reports, no task guidance
    private com.superagent.core.provider.ModelRouter modelRouter; // null in tests: the default model, no stand-in

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setModelRouter(com.superagent.core.provider.ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }
    private com.superagent.core.usage.UsageRecorder usage; // null in tests: tokens aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageRecorder(com.superagent.core.usage.UsageRecorder usage) {
        this.usage = usage;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTaskResults(ChatTaskResults taskResults) {
        this.taskResults = taskResults;
    }

    /** Task and automation tools exposed by SuperAgent's own MCP server, allowlisted so the chat agent may call them. */
    private static final List<String> SUPERAGENT_MCP_TOOLS = List.of(
            "mcp__superagent__create_task", "mcp__superagent__list_tasks", "mcp__superagent__get_task",
            "mcp__superagent__send_task_message", "mcp__superagent__stop_task", "mcp__superagent__delete_task",
            "mcp__superagent__create_automation", "mcp__superagent__list_automations",
            "mcp__superagent__get_automation", "mcp__superagent__update_automation",
            "mcp__superagent__set_automation_enabled", "mcp__superagent__run_automation_now",
            "mcp__superagent__delete_automation", "mcp__superagent__get_current_time");

    public ChatService(
            ChatStore store,
            AgentStore agentStore,
            SettingsService settingsService,
            ProviderService providerService,
            ImageAttachmentStore images,
            FileService files,
            SkillService skills,
            AgentMemoryService memory,
            AgentLogService runLog,
            List<AgentRunner> agentRunners,
            McpGrants mcpGrants,
            AgentMemberStore members,
            UserStore users,
            @Value("${superagent.agent.workspace-dir:./data/workspaces}") String workspaceDir,
            @Value("${superagent.agent.default-model:}") String defaultModel,
            @Value("${superagent.agent.allowed-tools:Read,Glob,Grep,LS,Bash}") String allowedTools,
            @Value("${superagent.agent.skip-permissions:false}") boolean skipPermissions,
            @Value("${superagent.chat.sse-timeout-ms:600000}") long sseTimeoutMs,
            @Value("${superagent.mcp.enabled-in-chat:true}") boolean mcpInChat) {
        this.store = store;
        this.agentStore = agentStore;
        this.members = members;
        this.users = users;
        this.settingsService = settingsService;
        this.providerService = providerService;
        this.images = images;
        this.files = files;
        this.skills = skills;
        this.memory = memory;
        this.runLog = runLog;
        for (AgentRunner runner : agentRunners) {
            this.runners.put(runner.type(), runner);
        }
        this.workspaceRoot = Path.of(workspaceDir).toAbsolutePath().normalize();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? null : defaultModel.trim();
        List<String> tools = new ArrayList<>(parseTools(allowedTools));
        this.skipPermissions = skipPermissions;
        this.sseTimeoutMs = sseTimeoutMs;
        if (mcpInChat && mcpGrants.available()) {
            // Expose SuperAgent's own MCP so the chat agent can create/steer tasks as tools.
            this.mcpGrants = mcpGrants;
            tools.addAll(SUPERAGENT_MCP_TOOLS);
            tools.addAll(McpGrants.WORK_TOOLS);
            tools.addAll(AgentCollabMcpTools.TOOLS);
            tools.addAll(McpGrants.SKILL_CHAT_TOOLS);
            tools.addAll(com.superagent.agent.AgentSkillMcpTools.TOOLS);
        } else {
            this.mcpGrants = null;
        }
        this.allowedTools = List.copyOf(tools);
        log.info("ChatService ready: runners={}, workspaceRoot={}, model={}, skipPermissions={}, superagentMcp={}",
                runners.keySet(), workspaceRoot, this.defaultModel, skipPermissions, this.mcpGrants != null);
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


    private com.superagent.core.timing.TurnTimingStore timings; // null in tests: timings aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTimings(com.superagent.core.timing.TurnTimingStore timings) {
        this.timings = timings;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCredentials(CredentialService credentials) {
        this.credentials = credentials;
    }

    // Looked up per turn: the customizer (the SuperAgent's) itself depends on this service.
    private org.springframework.beans.factory.ObjectProvider<AgentTurnCustomizer> customizers;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCustomizers(org.springframework.beans.factory.ObjectProvider<AgentTurnCustomizer> customizers) {
        this.customizers = customizers;
    }

    private com.superagent.platform.connector.ConnectorService connectors; // null in tests: no company connectors
    private com.superagent.core.project.ProjectAccess projects; // null in tests: no projects

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjects(com.superagent.core.project.ProjectAccess projects) {
        this.projects = projects;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConnectors(com.superagent.platform.connector.ConnectorService connectors) {
        this.connectors = connectors;
    }

    // --- session management ---

    public SessionDto createSession(CreateSessionRequest request) {
        String id = UUID.randomUUID().toString();
        AgentType agentType = AgentType.fromString(request == null ? null : request.agentType());
        Path workspace = TenantPaths.of(workspaceRoot).resolve(id);
        try {
            Files.createDirectories(workspace);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create workspace for session " + id, e);
        }
        String title = request != null && request.title() != null && !request.title().isBlank()
                ? request.title().trim() : "New chat";
        String agentId = request != null && request.agentId() != null && !request.agentId().isBlank()
                ? request.agentId().trim() : null;
        Instant now = Instant.now();
        ChatSession session = new ChatSession(id, title, agentType, null,
                workspace.toString(), agentId, SessionStatus.ACTIVE, now, now);
        store.createSession(session);
        log.info("created session {} ({}, agent={})", id, agentType, agentId);
        return toDto(session);
    }

    /** The sessions of agents the user owns or collaborates on; never anyone else's. */
    public List<SessionDto> listSessions(String user) {
        return store.listSessions().stream()
                .filter(s -> s.status() != SessionStatus.DELETED && canAccess(s, user))
                .map(ChatService::toDto).toList();
    }

    /**
     * Creating a session through the API: only on an agent the caller owns. Attaching one to someone else's
     * agent would make it that agent's current session, so their next turn would land in it.
     */
    public SessionDto createSessionFor(CreateSessionRequest request, String user) {
        String agentId = request == null ? null : request.agentId();
        boolean owns = agentId != null && agentStore.find(agentId)
                .filter(a -> a.status() != AgentStatus.DELETED && user != null && user.equals(a.owner())).isPresent();
        if (!owns) {
            throw new SessionNotFoundException(agentId == null ? "(no agent)" : agentId);
        }
        return createSession(request);
    }

    /**
     * A session that belongs to no agent's conversation (so it never shows in a chat history): the given id,
     * created with its own workspace the first time it's asked for. The id must be a UUID (Claude Code uses it
     * as its conversation id).
     */
    public ChatSession ensureDetachedSession(String id, String title) {
        return store.findSession(id).orElseGet(() -> {
            Path workspace = TenantPaths.of(workspaceRoot).resolve(id);
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create workspace for session " + id, e);
            }
            Instant now = Instant.now();
            ChatSession session = new ChatSession(id, title, AgentType.CLAUDE, null, workspace.toString(), null,
                    SessionStatus.ACTIVE, now, now);
            store.createSession(session);
            return session;
        });
    }

    /** The agent's current session — the one its next turn continues — or null if it has none. */
    public String currentSessionId(String agentId) {
        return currentSession(agentId).map(ChatSession::id).orElse(null);
    }

    private Optional<ChatSession> currentSession(String agentId) {
        return store.listSessionsByAgent(agentId).stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .max(Comparator.comparing(ChatSession::createdAt));
    }

    /**
     * The session an agent's turn on {@code model} runs in. An agent has many sessions over time: a turn
     * on a different model than the current session's starts a new one (same workspace, so files carry
     * over), and the agent's long-term memory — not the old conversation — is what the new one starts from.
     */
    public ChatSession sessionForTurn(Agent agent, String model) {
        return sessionForTurn(agent, model, false);
    }

    /** {@code modelDown}: the move is because the previous model couldn't run, not because the choice changed. */
    private ChatSession sessionForTurn(Agent agent, String model, boolean modelDown) {
        ChatSession current = currentSession(agent.id()).orElse(null);
        if (current == null || memory.modelChanged(agent.id(), model)) {
            boolean switching = current != null;
            String previous = memory.lastModel(agent.id());
            current = startSession(agent, current == null ? null : current.workspaceDir(), model,
                    !switching ? ChatSession.FIRST : modelDown ? ChatSession.MODEL_DOWN : ChatSession.MODEL_CHANGED);
            log.info("agent {} starts session {} on model {}", agent.id(), current.id(), model);
            runLog.info(agent, AgentLogService.SESSION, "开新会话 #" + sessionNumber(agent.id(), current.id())
                    + (switching ? "（切换模型：" + modelLabel(previous) + " → " + modelLabel(model) + "）" : "（首次对话）"),
                    switching ? "新会话从 Agent 记忆和最近的对话原文继续" : null);
            if (switching) {
                memory.consolidateSoon(agent);
            }
        }
        memory.recordModel(agent, model);
        return current;
    }

    /**
     * Gives up on the agent's current session (it kept failing) and moves it to a fresh one in the same
     * workspace. Users never see sessions — the new one picks up from memory and the last few messages.
     */
    public ChatSession rotateSession(Agent agent) {
        String workspace = currentSession(agent.id()).map(ChatSession::workspaceDir).orElse(null);
        ChatSession fresh = startSession(agent, workspace, memory.lastModel(agent.id()), ChatSession.FAILURES);
        log.info("agent {} moved to fresh session {}", agent.id(), fresh.id());
        runLog.warn(agent, AgentLogService.SESSION, "会话连续失败 " + memory.failureLimit() + " 次，已切换到新会话 #"
                + sessionNumber(agent.id(), fresh.id()) + " 并重试", "新会话从 Agent 记忆和最近的对话原文继续；已触发记忆沉淀");
        memory.consolidateSoon(agent);
        return fresh;
    }

    /** 1-based position of a session among the agent's sessions, for the run log (users never see ids). */
    private int sessionNumber(String agentId, String sessionId) {
        List<ChatSession> sessions = store.listSessionsByAgent(agentId).stream()
                .sorted(Comparator.comparing(ChatSession::createdAt)).toList();
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).id().equals(sessionId)) {
                return i + 1;
            }
        }
        return sessions.size();
    }

    private static String modelLabel(String model) {
        return model == null || model.isBlank() ? "默认模型" : model;
    }

    /** One line per turn: how it ended, on which model, how long it took, and how a failure was counted. */
    private void logTurn(Agent agent, String model, long startedAt, Attempt attempt, boolean retried, boolean stopped) {
        if (agent == null) {
            return;
        }
        String took = String.format("%.1fs", (System.currentTimeMillis() - startedAt) / 1000.0);
        String where = "模型 " + modelLabel(model) + " · 会话 #" + sessionNumber(agent.id(), attempt.session().id())
                + " · 用时 " + took;
        if (stopped) {
            runLog.info(agent, AgentLogService.TURN, "一轮被用户停止 · " + where, null);
        } else if (!attempt.failed()) {
            runLog.info(agent, AgentLogService.TURN, (retried ? "完成一轮（换新会话重试后成功）· " : "完成一轮 · ") + where,
                    attempt.error() != null ? "过程中出现过错误：" + attempt.error() : null);
        } else if (!memory.countsAgainstSession(attempt.error())) {
            runLog.warn(agent, AgentLogService.TURN, "一轮失败（网络类问题，不计入会话失败）· " + where, attempt.error());
        } else {
            runLog.error(agent, AgentLogService.TURN, "一轮失败（会话问题，连续第 " + memory.failureCount(agent.id())
                    + "/" + memory.failureLimit() + " 次）· " + where, attempt.error());
        }
    }

    private ChatSession startSession(Agent agent, String workspaceDir, String model, String reason) {
        String id = UUID.randomUUID().toString();
        if (workspaceDir == null) { // the agent's first session: its workspace is made now, and later ones share it
            Path workspace = TenantPaths.of(workspaceRoot).resolve(id);
            try {
                Files.createDirectories(workspace);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create workspace for session " + id, e);
            }
            workspaceDir = workspace.toString();
        }
        Instant now = Instant.now();
        ChatSession session = new ChatSession(id, agent.name(), AgentType.CLAUDE, null,
                workspaceDir, agent.id(), SessionStatus.ACTIVE, now, now, model, reason, 0);
        store.createSession(session);
        return session;
    }

    /** The agent's sessions, newest first: the one its next turn continues, then the ones before it. */
    public List<com.superagent.api.chat.AgentSessionDto> agentSessions(String agentId) {
        List<ChatSession> sessions = store.listSessionsByAgent(agentId).stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .sorted(Comparator.comparing(ChatSession::createdAt)).toList();
        List<com.superagent.api.chat.AgentSessionDto> out = new ArrayList<>();
        for (int i = sessions.size() - 1; i >= 0; i--) {
            ChatSession s = sessions.get(i);
            int turns = (int) store.listMessages(s.id()).stream().filter(m -> m.role() == MessageRole.ASSISTANT).count();
            out.add(new com.superagent.api.chat.AgentSessionDto(s.id(), i + 1, i == sessions.size() - 1, s.model(),
                    s.reason(), s.cliRestarts(), turns, s.createdAt(), s.updatedAt()));
        }
        return out;
    }

    /** Every session the agent has had, oldest first, each with its messages — the agent's whole conversation. */
    public List<ChatSegmentDto> agentHistory(String agentId) {
        return store.listSessionsByAgent(agentId).stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .sorted(Comparator.comparing(ChatSession::createdAt))
                .map(s -> new ChatSegmentDto(s.id(), s.createdAt(),
                        store.listMessages(s.id()).stream().map(m -> toMessageDto(s.id(), m)).toList()))
                .toList();
    }

    public SessionDto renameSession(String sessionId, String title) {
        ChatSession s = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        String clean = title == null || title.isBlank() ? "Untitled" : title.trim();
        ChatSession renamed = new ChatSession(s.id(), clean, s.agentType(), s.agentSessionId(),
                s.workspaceDir(), s.agentId(), s.status(), s.createdAt(), s.updatedAt(), s.model(), s.reason(), s.cliRestarts());
        store.updateSession(renamed);
        return toDto(renamed);
    }

    /** Soft delete: marks the session DELETED so it drops out of the list; the row is kept. */
    public void deleteSession(String sessionId) {
        ChatSession s = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        store.updateSession(new ChatSession(s.id(), s.title(), s.agentType(), s.agentSessionId(),
                s.workspaceDir(), s.agentId(), SessionStatus.DELETED, s.createdAt(), Instant.now(), s.model(), s.reason(),
                s.cliRestarts()));
    }

    public SessionDetailDto getSession(String sessionId) {
        ChatSession session = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        List<MessageDto> messages = store.listMessages(sessionId).stream()
                .map(m -> toMessageDto(sessionId, m)).toList();
        return new SessionDetailDto(toDto(session), messages);
    }

    /**
     * A session that belongs to another user's agent reads as "not found". Sessions with no agent
     * (from before agents) stay open to any signed-in user, as they were.
     */
    public void requireAccess(String sessionId, String user) {
        ChatSession session = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        if (!canAccess(session, user)) {
            throw new SessionNotFoundException(sessionId);
        }
    }

    /**
     * Owner or collaborator of the session's agent. A session with no agent (from before agents existed)
     * belongs to no one, so no one may open it — it used to be open to every signed-in user.
     */
    private boolean canAccess(ChatSession session, String user) {
        if (user == null || session.agentId() == null || session.agentId().isBlank()) {
            return false;
        }
        return agentStore.find(session.agentId())
                .filter(a -> user.equals(a.owner())
                        || members.members(a.id()).stream().anyMatch(m -> m.username().equals(user))
                        || (projects != null && projects.sharesProject(a.id(), user)))
                .isPresent();
    }

    // --- sending a message (streamed) ---

    private QuotaService quotas; // null in tests: no limits

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void useQuotas(QuotaService quotas) {
        this.quotas = quotas;
    }

    public SseEmitter sendMessage(String sessionId, String content) {
        return sendMessage(sessionId, content, List.of(), List.of(), null);
    }

    /** {@code user} is who is chatting: tasks the agent creates through MCP belong to them (null = default owner). */
    /** {@code fileIds}: files the user uploaded for this message (see FileService#upload); they go into the workspace. */
    public SseEmitter sendMessage(String requestedSessionId, String content, List<ImageUploadDto> uploads,
                                  List<String> fileIds, String user) {
        com.superagent.core.timing.TurnTimer timer = new com.superagent.core.timing.TurnTimer(); // from the request on
        if (quotas != null) {
            quotas.checkTokens();
        }
        ChatSession session = store.findSession(requestedSessionId)
                .orElseThrow(() -> new SessionNotFoundException(requestedSessionId));
        String sessionId = session.id();
        Agent agent = session.agentId() == null || session.agentId().isBlank() ? null
                : agentStore.find(session.agentId()).orElse(null);
        AgentTurnCustomizer special = agent == null || customizers == null ? null
                : customizers.orderedStream().filter(c -> c.handles(agent)).findFirst().orElse(null);
        if (agent != null && agent.isSuper() && special == null) {
            throw new IllegalStateException("SuperAgent chat is not available");
        }
        if (agent != null && projects != null && projects.archived(agent.id())) {
            throw new IllegalArgumentException("这个 Agent 所在的项目已归档，只能查看，不能再对话");
        }
        // One turn at a time: in a shared agent, another member's turn may be running.
        String liveKey = agent != null ? agent.id() : sessionId;
        LiveTurn running = liveTurns.get(liveKey);
        if (running != null) {
            throw new ChatBusyException(running.byName() + " 触发的回复正在进行中，请稍候");
        }
        String model = resolveModel(agent);
        if (agent != null) {
            // The agent's current session, or a new one if the model changed; never an older one the page still shows.
            session = sessionForTurn(agent, model);
            sessionId = session.id();
        }
        // The model decides the CLI (a Codex model must run under codex); unknown models keep the session's own.
        AgentType agentType = providerService.cliFor(model).map(AgentType::fromString)
                .filter(runners::containsKey).orElse(session.agentType());
        AgentRunner runner = runners.get(agentType);
        if (runner == null) {
            throw new IllegalStateException("No runner for agent type " + agentType);
        }
        // Character first, then the index of the agent's skills (their full text is read on demand).
        String systemPrompt;
        if (special != null) {
            systemPrompt = special.systemPrompt(agent);
        } else {
            String character = agent == null ? null : AgentCharacters.systemPrompt(agent);
            String skillIndex = skills.install(session.workspaceDir(),
                    agent == null ? List.of() : skills.forAgent(agent.id(), agent.owner()), mcpGrants != null);
            systemPrompt = joinPrompt(joinPrompt(joinPrompt(character, skillIndex), mcpGrants == null ? null : McpGrants.FILES_HINT),
                    worksInTasks(agent) ? ChatTaskResults.GUIDE : null);
        }

        // Images are stored under the user message's id before it's appended, so a bad upload stores nothing.
        String userMessageId = UUID.randomUUID().toString();
        List<ImageAttachment> attached = new ArrayList<>(images.save(sessionId, userMessageId, uploads));
        FileService.Delivered delivered = fileIds == null || fileIds.isEmpty() ? null
                : FileService.deliver(files.attach(fileIds, user, sessionId, userMessageId), session.workspaceDir());
        if (delivered != null) {
            attached.addAll(delivered.images());
        }
        String text = content == null ? "" : content;
        store.appendMessage(new ChatMessage(userMessageId, sessionId, MessageRole.USER, text, Instant.now(), user));
        if (agent != null) {
            files.saveLinks(user, agent.id(), null, sessionId, userMessageId, text); // links are resources too
        }
        String prompt = text.isBlank() && !attached.isEmpty() ? ImageAttachmentStore.IMAGE_ONLY_PROMPT : text;
        if (delivered != null) {
            prompt = (text.isBlank() && attached.isEmpty() ? "" : prompt + "\n\n") + delivered.note();
        }
        // In a shared agent the model must know who is asking, so it can tell the members' requests apart.
        if (agent != null && !members.members(agent.id()).isEmpty()) {
            prompt = "[" + displayName(user) + "]：" + prompt;
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        String assistantMessageId = UUID.randomUUID().toString();
        Cancellation cancellation = new Cancellation();
        LiveTurn live = new LiveTurn(user, displayName(user), Instant.now());
        if (liveTurns.putIfAbsent(liveKey, live) != null) {
            throw new ChatBusyException("另一条消息刚刚触发了回复，请稍候");
        }
        activeRuns.put(sessionId, cancellation);

        ChatSession turn = session;
        // A session's first turn starts from memory plus the latest messages; later turns resume their history
        // (the memory alone still rides along, for when the CLI has to start over on its own).
        String agentMemory = agent == null ? null
                : session.agentSessionId() == null ? memory.context(agent, Set.of(userMessageId)) : memory.read(agent);
        String turnPrompt = prompt;
        executor.execute(TenantContext.wrap(() -> {
            try {
                runTurn(agent, turn, agentType, runner, model, systemPrompt, agentMemory, turnPrompt, attached,
                        user, userMessageId, assistantMessageId, new TurnStream(emitter, live, agent, special, timer), cancellation);
            } finally {
                liveTurns.remove(liveKey, live);
                live.end();
            }
        }));
        return emitter;
    }

    private static String joinPrompt(String a, String b) {
        return a == null ? b : b == null ? a : a + "\n\n" + b;
    }

    /** Model precedence: the agent's own override > admin's system default > deployment config. */
    /** An agent with the task tools does its work in tasks: talk here, work there. */
    private boolean worksInTasks(Agent agent) {
        return agent != null && !agent.isSuper() && mcpGrants != null && taskResults != null;
    }

    /**
     * Every conversation runs on the admin's default model, not on a model of its own: the chat thinks and hands out
     * work, which wants the strongest; the work itself runs in tasks, each on a model chosen for it.
     */
    public String resolveModel(Agent agent) {
        if (agent != null && modelRouter != null) {
            List<String> usable = modelRouter.chatModels();
            if (!usable.isEmpty()) {
                return usable.get(0); // the default while it works; a stand-in nobody has to notice when it doesn't
            }
        }
        String model = settingsService.agentDefaultModel();
        return model != null ? model : defaultModel;
    }

    /**
     * Stops the in-flight turn for a session, if any — or for its agent's current session, since a model
     * change moves the turn into a new session the page doesn't know about yet.
     */
    public void stopGeneration(String sessionId) {
        Optional.ofNullable(activeRuns.get(sessionId)).ifPresent(Cancellation::cancel);
        store.findSession(sessionId).map(ChatSession::agentId).filter(a -> !a.isBlank())
                .map(this::currentSessionId).map(activeRuns::get).ifPresent(Cancellation::cancel);
    }

    /** Edits a stored message's content in place (record only; does not re-run the agent). */
    public void editMessage(String sessionId, String messageId, String content) {
        store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        store.updateMessage(sessionId, messageId, content);
    }

    /**
     * Starts the line a failed turn's error is kept under in its reply, so reopening the chat still says what went
     * wrong instead of showing an empty reply. The page styles what follows it as an error.
     */
    public static final String ERROR_MARK = "⚠️ 出错了：";

    /** The reply as stored: what was said, then the error if the turn failed (a stopped turn isn't an error). */
    static String storedReply(String text, String error, boolean cancelled) {
        String said = text == null ? "" : text;
        if (error == null || error.isBlank() || cancelled) {
            return said;
        }
        return (said.isBlank() ? "" : said.stripTrailing() + "\n\n") + ERROR_MARK + error.strip();
    }

    /** How one attempt at a turn went. {@code error} set + blank {@code text} = the attempt failed. */
    /** How many other models a turn tries when its model can't run. */
    private static final int MODEL_HOPS = 2;

    private record Attempt(ChatSession session, String text, String error, String agentSessionId) {
        boolean failed() {
            return text.isBlank() && error != null;
        }
    }

    /**
     * Runs a turn. If it fails and that tips the session over its failure limit (network trouble never
     * counts), the agent moves to a fresh session — memory plus the last messages — and the same message is
     * tried once more there. All of it is invisible to the user, who only ever talks to the agent: errors are
     * held back until it's clear the turn really failed.
     */
    private void runTurn(Agent agent, ChatSession session, AgentType agentType, AgentRunner runner, String model,
                         String systemPrompt, String agentMemory, String prompt, List<ImageAttachment> attached,
                         String user, String userMessageId, String assistantMessageId, TurnStream emitter,
                         Cancellation cancellation) {
        long startedAt = System.currentTimeMillis();
        boolean retried = false;
        boolean saved = false;
        try {
            Attempt attempt = attempt(session, agentType, runner, model, systemPrompt, agentMemory, prompt, attached,
                    user, assistantMessageId, emitter, cancellation);
            // The model itself can't run (endpoint, every key, quota, gone): the same turn again on the next model the
            // conversation may use, quietly. The failed one is passed over for a while, then tried again.
            for (int hop = 0; hop < MODEL_HOPS && agent != null && modelRouter != null && attempt.failed()
                    && !cancellation.isCancelled() && EndpointFailures.isModelFailure(attempt.error()); hop++) {
                modelRouter.reportFailure(model, attempt.error());
                String failed = model;
                String next = modelRouter.chatModels().stream().filter(m -> !m.equals(failed)).findFirst().orElse(null);
                AgentType nextType = next == null ? null : providerService.cliFor(next).map(AgentType::fromString)
                        .filter(runners::containsKey).orElse(null);
                if (nextType == null) {
                    break;
                }
                log.warn("agent {}: {} can't run ({}); this turn moves to {}", agent.id(), model, attempt.error(), next);
                model = next;
                agentType = nextType;
                runner = runners.get(nextType);
                ChatSession moved = sessionForTurn(agent, next, true); // another model: a new session, from memory + recent messages
                attempt = attempt(moved, agentType, runner, model, systemPrompt, memory.context(agent, Set.of(userMessageId)),
                        prompt, attached, user, assistantMessageId, emitter, cancellation);
            }
            if (agent != null && !cancellation.isCancelled()) {
                if (!attempt.failed()) {
                    memory.recordSuccess(agent);
                } else if (memory.recordFailure(agent, attempt.error())) {
                    retried = true;
                    ChatSession fresh = rotateSession(agent);
                    attempt = attempt(fresh, agentType, runner, model, systemPrompt,
                            memory.context(agent, Set.of(userMessageId)), prompt, attached, user, assistantMessageId,
                            emitter, cancellation);
                    if (attempt.failed()) {
                        memory.recordFailure(agent, attempt.error());
                    } else {
                        memory.recordSuccess(agent);
                    }
                }
            }
            store.appendMessage(new ChatMessage(assistantMessageId, attempt.session().id(),
                    MessageRole.ASSISTANT, storedReply(attempt.text(), attempt.error(), cancellation.isCancelled()), Instant.now()));
            saved = true;
            saveTiming(emitter, attempt.session().id(), assistantMessageId); // before "done": the page reloads then
            persistSessionUpdate(attempt.session(), agentType, attempt.agentSessionId(), prompt);
            logTurn(agent, model, startedAt, attempt, retried, cancellation.isCancelled());

            if (attempt.error() != null && !cancellation.isCancelled()) {
                send(emitter, new StreamEventDto("error", -1, attempt.error()));
            }
            if (cancellation.isCancelled()) {
                send(emitter, new StreamEventDto("stopped", -1, attempt.text()));
            }
            send(emitter, new StreamEventDto("done", -1, attempt.text()));
            emitter.sender().complete();
        } catch (Exception e) {
            log.error("turn failed for session {}", session.id(), e);
            String detail = e.getMessage() != null ? e.getMessage() : "agent run failed";
            if (!saved) { // keep the error in the conversation, or reopening it shows nothing went wrong
                store.appendMessage(new ChatMessage(assistantMessageId, session.id(), MessageRole.ASSISTANT,
                        storedReply("", detail, false), Instant.now()));
                saveTiming(emitter, session.id(), assistantMessageId);
            }
            send(emitter, new StreamEventDto("error", -1, detail));
            emitter.sender().complete();
        } finally {
            activeRuns.remove(session.id(), cancellation);
        }
    }

    /** One run of the turn in {@code session}: output streams live, errors are only collected. */
    private Attempt attempt(ChatSession session, AgentType agentType, AgentRunner runner, String model,
                            String systemPrompt, String agentMemory, String prompt, List<ImageAttachment> attached,
                            String user, String assistantMessageId, TurnStream emitter, Cancellation cancellation) {
        StringBuilder textAccumulator = new StringBuilder();
        AtomicReference<String> resultText = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        String agentSessionId = agentSessionIdFor(session, agentType);
        AtomicReference<String> capturedAgentSessionId = new AtomicReference<>(agentSessionId);

        // The tokens go on whoever sent the message (in a shared agent, that member), not the agent's owner.
        com.superagent.core.usage.UsageContext paying = new com.superagent.core.usage.UsageContext(user,
                emitter.agent() != null && emitter.agent().isSuper() ? com.superagent.core.usage.UsageContext.SOURCE_SUPERAGENT
                        : com.superagent.core.usage.UsageContext.SOURCE_CHAT,
                session.agentId(), null, session.id(), agentType.cliName());
        AgentEventSink sink = emitter.timer().tapTools(com.superagent.core.usage.UsageRecorder.tap(usage, paying, event -> {
            store.appendEvent(new AgentEvent(session.id(), assistantMessageId, event.seq(),
                    event.type(), event.payload(), event.createdAt()));
            switch (event.type()) {
                case ERROR -> error.set(event.payload()); // held back: this attempt may yet be retried
                case TEXT -> textAccumulator.append(event.payload());
                case RESULT -> resultText.set(event.payload());
                case SYSTEM -> {
                    if (event.payload() != null && !event.payload().isBlank()) {
                        capturedAgentSessionId.set(event.payload());
                    }
                }
                default -> { /* thinking / tool use: forwarded below */ }
            }
            if (event.type() != AgentEventType.ERROR) {
                send(emitter, new StreamEventDto(event.type().name().toLowerCase(), event.seq(), event.payload()));
            }
        }));

        AgentTurnCustomizer special = emitter.special();
        McpGrants.Grant grant = special != null ? special.openGrant(emitter.agent(), user, session, assistantMessageId)
                : mcpGrants == null ? null : mcpGrants.openChat(user, session.agentId(), session.id(), assistantMessageId, session.workspaceDir());
        String cli = agentType.cliName();
        CredentialService.Choice choice = credentials == null ? new CredentialService.Choice(null) : credentials.current(cli);
        // The company's connectors, with the identity of whoever sent this message (their own authorizations).
        List<McpServerSpec> connected = connectors == null ? List.of() : connectors.mcpServersFor(user);
        List<String> tools = allowedTools;
        if (special != null) {
            // The built-in tools only: the chat MCP tools belong to a CHAT grant, not this agent's own.
            boolean shell = special.runsCommands(emitter.agent());
            tools = new ArrayList<>(allowedTools.stream().filter(t -> !t.startsWith("mcp__") && (shell || !t.equals("Bash"))).toList());
            tools.addAll(special.tools(emitter.agent()));
        }
        if (!connected.isEmpty()) {
            tools = new ArrayList<>(tools);
            tools.addAll(com.superagent.platform.connector.ConnectorService.toolPatterns(connected));
        }
        String sent = special != null ? special.prompt(emitter.agent(), session, prompt)
                : worksInTasks(emitter.agent()) ? taskResults.prompt(session.id(), prompt) : prompt;
        AgentRunRequest request = new AgentRunRequest(session.id(), agentSessionId, session.workspaceDir(), sent, model,
                tools, skipPermissions && (special == null || special.runsCommands(emitter.agent())), McpGrants.withServers(grant == null ? null : mcpGrants.mcpConfig(grant), connected),
                systemPrompt, attached, agentMemory, choice.credential());
        try {
            runOnce(runner, request, sink, cancellation, error, session, emitter.timer());
            // The endpoint or key failed (auth, quota, server down) before anything was said: switch to the
            // next key in the pool and try the same turn once more, so the user rarely sees the failure.
            boolean nothingSaid = textAccumulator.isEmpty() && resultText.get() == null;
            if (credentials != null && nothingSaid && error.get() != null && !cancellation.isCancelled()) {
                Optional<CredentialService.Choice> next = credentials.failover(cli, choice, error.get());
                if (next.isPresent()) {
                    log.info("session {}: retrying the turn on endpoint {}", session.id(), next.get().id());
                    error.set(null);
                    runOnce(runner, request.withCredential(next.get().credential()), sink, cancellation, error, session,
                            emitter.timer());
                }
            }
        } finally {
            if (grant != null) {
                mcpGrants.close(grant);
            }
        }
        String text = resultText.get() != null ? resultText.get() : textAccumulator.toString();
        return new Attempt(session, text == null ? "" : text, error.get(), capturedAgentSessionId.get());
    }

    private void runOnce(AgentRunner runner, AgentRunRequest request, AgentEventSink sink, Cancellation cancellation,
                         AtomicReference<String> error, ChatSession session, com.superagent.core.timing.TurnTimer timer) {
        timer.attemptStarted();
        try {
            runner.runChat(request, sink, cancellation);
        } catch (RuntimeException e) {
            // A runner that blows up is a failed attempt like any other: it may count against the session.
            log.warn("turn attempt failed in session {}: {}", session.id(), e.getMessage());
            error.set(e.getMessage() != null ? e.getMessage() : "agent run failed");
        } finally {
            timer.attemptEnded();
        }
    }

    /** Keeps where the reply's time went, shown under it; losing it must never cost the reply. */
    private void saveTiming(TurnStream stream, String sessionId, String messageId) {
        if (timings == null) {
            return;
        }
        try {
            timings.save(stream.timer().finish(sessionId, messageId, null));
        } catch (RuntimeException e) {
            log.warn("could not keep the timing of reply {}: {}", messageId, e.getMessage());
        }
    }

    /** The timings of these sessions' replies, for showing under them. */
    public List<com.superagent.core.timing.TurnTiming> timings(java.util.Collection<String> sessionIds) {
        return timings == null ? List.of() : timings.forSessions(sessionIds);
    }

    /**
     * The stored agent session id belongs to whichever CLI ran last ({@code session.agentType()}). After a
     * model switch to the other CLI, hand it an id it understands instead: Claude's conversation id is the
     * SuperAgent session id (its first turn passes {@code --session-id}; a missing one falls back to a fresh
     * conversation), and Codex finds its own thread from the session id on disk.
     */
    private static String agentSessionIdFor(ChatSession session, AgentType agentType) {
        if (agentType == session.agentType() || session.agentSessionId() == null) {
            return session.agentSessionId();
        }
        return session.id();
    }

    private void persistSessionUpdate(ChatSession session, AgentType agentType, String agentSessionId,
                                      String firstPrompt) {
        ChatSession updated = session;
        if (agentType != session.agentType()) {
            updated = session.withAgentType(agentType, agentSessionId);
        } else if (agentSessionId != null && !agentSessionId.equals(session.agentSessionId())) {
            updated = updated.withAgentSessionId(agentSessionId);
        }
        if ("New chat".equals(session.title())) {
            updated = updated.withTitle(deriveTitle(firstPrompt));
        } else {
            updated = updated.touched();
        }
        store.updateSession(updated);
    }

    private static String deriveTitle(String prompt) {
        String oneLine = prompt.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 48 ? oneLine : oneLine.substring(0, 48) + "…";
    }

    /**
     * Where a turn's events go — the sender's own stream and every other viewer of the agent — plus the agent and,
     * for a kind of agent another module runs differently (the SuperAgent), how its turns are set up.
     */
    record TurnStream(SseEmitter sender, LiveTurn live, Agent agent, AgentTurnCustomizer special,
                      com.superagent.core.timing.TurnTimer timer) {
    }

    private void send(TurnStream stream, StreamEventDto event) {
        send(stream.sender(), event);
        stream.live().publish(event);
    }

    private void send(SseEmitter emitter, StreamEventDto event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException | IllegalStateException e) {
            // client disconnected or emitter already completed; nothing to do
            log.debug("SSE send dropped ({}): {}", event.type(), e.getMessage());
        }
    }

    private static SessionDto toDto(ChatSession s) {
        return new SessionDto(s.id(), s.title(), s.agentType().name().toLowerCase(),
                s.status().name().toLowerCase(), s.agentId(), s.createdAt(), s.updatedAt());
    }

    private MessageDto toMessageDto(String sessionId, ChatMessage m) {
        List<String> urls = m.role() == MessageRole.USER
                ? images.urls(ApiPaths.SESSIONS + "/" + sessionId + "/messages", sessionId, m.id()) : List.of();
        return new MessageDto(m.id(), m.role().name().toLowerCase(), m.content(), m.createdAt(), urls,
                m.author(), m.author() == null ? null : displayName(m.author()));
    }

    // --- live view of a turn, for the other members of a shared agent ---

    /**
     * A turn in progress: who started it, and every event the sender's stream got, so each viewer of the agent
     * sees the same thing (thinking, tool calls, text, task and file cards). A viewer who joins late gets the
     * events so far, then the rest as they happen. Consecutive text / thinking deltas are merged to keep the
     * replay short.
     */
    static final class LiveTurn {
        private final String by;
        private final String byName;
        private final Instant startedAt;
        private final StringBuilder text = new StringBuilder();
        private final List<StreamEventDto> events = new ArrayList<>();
        private final List<SseEmitter> watchers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private String activity;
        private boolean ended;

        LiveTurn(String by, String byName, Instant startedAt) {
            this.by = by;
            this.byName = byName;
            this.startedAt = startedAt;
        }

        String by() {
            return by;
        }

        String byName() {
            return byName;
        }

        synchronized void publish(StreamEventDto event) {
            if ("text".equals(event.type()) && event.payload() != null) {
                text.append(event.payload());
            } else if ("tool_use".equals(event.type()) && event.payload() != null) {
                activity = event.payload().length() > 200 ? event.payload().substring(0, 200) + "…" : event.payload();
            }
            StreamEventDto last = events.isEmpty() ? null : events.get(events.size() - 1);
            if (last != null && last.type().equals(event.type()) && event.payload() != null
                    && ("text".equals(event.type()) || "thinking".equals(event.type()))) {
                events.set(events.size() - 1, new StreamEventDto(last.type(), event.seq(),
                        (last.payload() == null ? "" : last.payload()) + event.payload()));
            } else {
                events.add(event);
            }
            watchers.forEach(w -> deliver(w, event));
        }

        /** Replays the turn so far to a new viewer, then keeps it posted until the turn ends. */
        synchronized void watch(SseEmitter watcher) {
            events.forEach(e -> deliver(watcher, e));
            if (ended) {
                watcher.complete();
                return;
            }
            watchers.add(watcher);
            Runnable drop = () -> watchers.remove(watcher);
            watcher.onCompletion(drop);
            watcher.onTimeout(drop);
            watcher.onError(e -> drop.run());
        }

        synchronized void end() {
            ended = true;
            watchers.forEach(SseEmitter::complete);
            watchers.clear();
        }

        synchronized LiveTurnDto toDto(String sessionId) {
            return new LiveTurnDto(true, by, byName, startedAt, text.toString(), activity, sessionId);
        }

        private void deliver(SseEmitter watcher, StreamEventDto event) {
            try {
                watcher.send(SseEmitter.event().name(event.type()).data(event));
            } catch (IOException | IllegalStateException e) {
                watchers.remove(watcher); // that viewer left; the turn goes on
            }
        }
    }

    private Optional<LiveTurn> liveTurn(ChatSession session) {
        return Optional.ofNullable(liveTurns.get(session.agentId() != null && !session.agentId().isBlank()
                ? session.agentId() : session.id()));
    }

    /** What every viewer of the session polls: is a turn running, whose, and its text so far. */
    public LiveTurnDto live(String sessionId) {
        ChatSession session = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        return liveTurn(session).map(t -> t.toDto(currentSessionId(session.agentId()))).orElse(new LiveTurnDto(false, null, null, null, null, null,
                session.agentId() == null || session.agentId().isBlank() ? session.id() : currentSessionId(session.agentId())));
    }

    /**
     * A viewer's live feed of the running turn: the same events the sender's stream carries, from the start.
     * With no turn running it ends at once with a single "idle" event.
     */
    public SseEmitter watchLive(String sessionId) {
        ChatSession session = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        SseEmitter watcher = new SseEmitter(sseTimeoutMs);
        Optional<LiveTurn> live = liveTurn(session);
        if (live.isEmpty()) {
            send(watcher, new StreamEventDto("idle", -1, null));
            watcher.complete();
        } else {
            live.get().watch(watcher);
        }
        return watcher;
    }

    private String displayName(String userId) {
        if (userId == null) {
            return "有人";
        }
        return users.findById(userId).map(u -> u.displayName() == null || u.displayName().isBlank()
                ? u.username() : u.displayName()).orElse(userId);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
