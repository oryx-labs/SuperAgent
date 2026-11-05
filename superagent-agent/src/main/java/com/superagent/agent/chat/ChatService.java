package com.superagent.agent.chat;

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
import com.superagent.core.user.UserStore;
import com.superagent.knowledge.skill.SkillService;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import com.superagent.runtime.attachment.ImageAttachmentStore;
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
    private final ImageAttachmentStore images;
    private final String defaultModel;
    private final List<String> allowedTools;
    private final boolean skipPermissions;
    private final long sseTimeoutMs;
    private final McpGrants mcpGrants;   // null = chat agents get no SuperAgent MCP
    private final SkillService skills;
    private final AgentMemoryService memory;
    private final AgentLogService runLog;

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
            SkillService skills,
            AgentMemoryService memory,
            AgentLogService runLog,
            List<AgentRunner> agentRunners,
            McpGrants mcpGrants,
            AgentMemberStore members,
            UserStore users,
            @Value("${superagent.agent.workspace-dir:./data/workspaces}") String workspaceDir,
            @Value("${superagent.agent.default-model:}") String defaultModel,
            @Value("${superagent.agent.allowed-tools:Read,Glob,Grep,LS}") String allowedTools,
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
            tools.addAll(McpGrants.KNOWLEDGE_TOOLS);
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

    // --- session management ---

    public SessionDto createSession(CreateSessionRequest request) {
        String id = UUID.randomUUID().toString();
        AgentType agentType = AgentType.fromString(request == null ? null : request.agentType());
        Path workspace = workspaceRoot.resolve(id);
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

    public List<SessionDto> listSessions() {
        return store.listSessions().stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .map(ChatService::toDto).toList();
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
        ChatSession current = currentSession(agent.id()).orElse(null);
        if (current == null || memory.modelChanged(agent.id(), model)) {
            boolean switching = current != null;
            String previous = memory.lastModel(agent.id());
            current = startSession(agent, current == null ? null : current.workspaceDir());
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
        ChatSession fresh = startSession(agent, workspace);
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

    private ChatSession startSession(Agent agent, String workspaceDir) {
        if (workspaceDir == null) {
            return store.findSession(createSession(new CreateSessionRequest(null, agent.name(), null, agent.id())).id())
                    .orElseThrow();
        }
        Instant now = Instant.now();
        ChatSession session = new ChatSession(UUID.randomUUID().toString(), agent.name(), AgentType.CLAUDE, null,
                workspaceDir, agent.id(), SessionStatus.ACTIVE, now, now);
        store.createSession(session);
        return session;
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
                s.workspaceDir(), s.agentId(), s.status(), s.createdAt(), s.updatedAt());
        store.updateSession(renamed);
        return toDto(renamed);
    }

    /** Soft delete: marks the session DELETED so it drops out of the list; the row is kept. */
    public void deleteSession(String sessionId) {
        ChatSession s = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        store.updateSession(new ChatSession(s.id(), s.title(), s.agentType(), s.agentSessionId(),
                s.workspaceDir(), s.agentId(), SessionStatus.DELETED, s.createdAt(), Instant.now()));
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
        if (session.agentId() != null && !session.agentId().isBlank()) {
            agentStore.find(session.agentId())
                    .filter(a -> a.owner() == null || a.owner().equals(user)
                            || members.members(a.id()).stream().anyMatch(m -> m.username().equals(user)))
                    .orElseThrow(() -> new SessionNotFoundException(sessionId));
        }
    }

    // --- sending a message (streamed) ---

    public SseEmitter sendMessage(String sessionId, String content) {
        return sendMessage(sessionId, content, List.of(), null);
    }

    /** {@code user} is who is chatting: tasks the agent creates through MCP belong to them (null = default owner). */
    public SseEmitter sendMessage(String requestedSessionId, String content, List<ImageUploadDto> uploads, String user) {
        ChatSession session = store.findSession(requestedSessionId)
                .orElseThrow(() -> new SessionNotFoundException(requestedSessionId));
        String sessionId = session.id();
        Agent agent = session.agentId() == null || session.agentId().isBlank() ? null
                : agentStore.find(session.agentId()).orElse(null);
        if (agent != null && agent.isSuper()) {
            // The SuperAgent doesn't answer live: messages go to its inbox and are worked through in rounds.
            throw new IllegalArgumentException("Send messages to SuperAgent through its inbox");
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
        String character = agent == null ? null : AgentCharacters.systemPrompt(agent);
        String skillIndex = skills.install(session.workspaceDir(),
                agent == null ? List.of() : skills.forAgent(agent.id(), agent.owner()), mcpGrants != null);
        String systemPrompt = character == null ? skillIndex
                : skillIndex == null ? character : character + "\n\n" + skillIndex;

        // Images are stored under the user message's id before it's appended, so a bad upload stores nothing.
        String userMessageId = UUID.randomUUID().toString();
        List<ImageAttachment> attached = images.save(sessionId, userMessageId, uploads);
        String text = content == null ? "" : content;
        store.appendMessage(new ChatMessage(userMessageId, sessionId, MessageRole.USER, text, Instant.now(), user));
        String prompt = text.isBlank() && !attached.isEmpty() ? ImageAttachmentStore.IMAGE_ONLY_PROMPT : text;
        // In a shared agent the model must know who is asking, so it can tell the members' requests apart.
        if (agent != null && !members.members(agent.id()).isEmpty()) {
            prompt = "[" + displayName(user) + "]：" + prompt;
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        String assistantMessageId = UUID.randomUUID().toString();
        Cancellation cancellation = new Cancellation();
        LiveTurn live = new LiveTurn(user, displayName(user), Instant.now(), new StringBuilder(), new AtomicReference<>());
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
        executor.execute(() -> {
            try {
                runTurn(agent, turn, agentType, runner, model, systemPrompt, agentMemory, turnPrompt, attached,
                        user, userMessageId, assistantMessageId, emitter, cancellation);
            } finally {
                liveTurns.remove(liveKey, live);
            }
        });
        return emitter;
    }

    /** Model precedence: the agent's own override > admin's system default > deployment config. */
    private String resolveModel(Agent agent) {
        if (agent != null && agent.model() != null && !agent.model().isBlank()) {
            return agent.model().trim();
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

    /** How one attempt at a turn went. {@code error} set + blank {@code text} = the attempt failed. */
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
                         String user, String userMessageId, String assistantMessageId, SseEmitter emitter,
                         Cancellation cancellation) {
        long startedAt = System.currentTimeMillis();
        boolean retried = false;
        try {
            Attempt attempt = attempt(session, agentType, runner, model, systemPrompt, agentMemory, prompt, attached,
                    user, assistantMessageId, emitter, cancellation);
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
                    MessageRole.ASSISTANT, attempt.text(), Instant.now()));
            persistSessionUpdate(attempt.session(), agentType, attempt.agentSessionId(), prompt);
            logTurn(agent, model, startedAt, attempt, retried, cancellation.isCancelled());

            if (attempt.error() != null && !cancellation.isCancelled()) {
                send(emitter, new StreamEventDto("error", -1, attempt.error()));
            }
            if (cancellation.isCancelled()) {
                send(emitter, new StreamEventDto("stopped", -1, attempt.text()));
            }
            send(emitter, new StreamEventDto("done", -1, attempt.text()));
            emitter.complete();
        } catch (Exception e) {
            log.error("turn failed for session {}", session.id(), e);
            String detail = e.getMessage() != null ? e.getMessage() : "agent run failed";
            send(emitter, new StreamEventDto("error", -1, detail));
            emitter.complete();
        } finally {
            activeRuns.remove(session.id(), cancellation);
        }
    }

    /** One run of the turn in {@code session}: output streams live, errors are only collected. */
    private Attempt attempt(ChatSession session, AgentType agentType, AgentRunner runner, String model,
                            String systemPrompt, String agentMemory, String prompt, List<ImageAttachment> attached,
                            String user, String assistantMessageId, SseEmitter emitter, Cancellation cancellation) {
        StringBuilder textAccumulator = new StringBuilder();
        AtomicReference<String> resultText = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        String agentSessionId = agentSessionIdFor(session, agentType);
        AtomicReference<String> capturedAgentSessionId = new AtomicReference<>(agentSessionId);

        AgentEventSink sink = event -> {
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
            liveTurn(session).ifPresent(live -> live.observe(event.type(), event.payload()));
            if (event.type() != AgentEventType.ERROR) {
                send(emitter, new StreamEventDto(event.type().name().toLowerCase(), event.seq(), event.payload()));
            }
        };

        McpGrants.Grant grant = mcpGrants == null ? null : mcpGrants.openChat(user, session.agentId(), session.workspaceDir());
        try {
            runner.run(new AgentRunRequest(session.id(), agentSessionId, session.workspaceDir(), prompt, model,
                    allowedTools, skipPermissions, grant == null ? null : mcpGrants.mcpConfig(grant), systemPrompt,
                    attached, agentMemory), sink, cancellation);
        } catch (RuntimeException e) {
            // A runner that blows up is a failed attempt like any other: it may count against the session.
            log.warn("turn attempt failed in session {}: {}", session.id(), e.getMessage());
            error.set(e.getMessage() != null ? e.getMessage() : "agent run failed");
        } finally {
            if (grant != null) {
                mcpGrants.close(grant);
            }
        }
        String text = resultText.get() != null ? resultText.get() : textAccumulator.toString();
        return new Attempt(session, text == null ? "" : text, error.get(), capturedAgentSessionId.get());
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
            updated = new ChatSession(session.id(), session.title(), agentType, agentSessionId,
                    session.workspaceDir(), session.agentId(), session.status(), session.createdAt(), Instant.now());
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

    /** A turn in progress: who started it and what the agent has said so far. */
    record LiveTurn(String by, String byName, Instant startedAt, StringBuilder text, AtomicReference<String> activity) {
        void observe(AgentEventType type, String payload) {
            if (type == AgentEventType.TEXT && payload != null) {
                synchronized (text) {
                    text.append(payload);
                }
            } else if (type == AgentEventType.TOOL_USE && payload != null) {
                activity.set(payload.length() > 200 ? payload.substring(0, 200) + "…" : payload);
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
        return liveTurn(session).map(t -> {
            String text;
            synchronized (t.text()) {
                text = t.text().toString();
            }
            return new LiveTurnDto(true, t.by(), t.byName(), t.startedAt(), text, t.activity().get(),
                    currentSessionId(session.agentId()));
        }).orElse(new LiveTurnDto(false, null, null, null, null, null,
                session.agentId() == null || session.agentId().isBlank() ? session.id() : currentSessionId(session.agentId())));
    }

    private String displayName(String username) {
        if (username == null) {
            return "有人";
        }
        return users.findByUsername(username).map(u -> u.displayName() == null || u.displayName().isBlank()
                ? u.username() : u.displayName()).orElse(username);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
