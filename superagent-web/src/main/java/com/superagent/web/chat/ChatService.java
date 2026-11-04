package com.superagent.web.chat;

import com.superagent.api.ApiPaths;
import com.superagent.api.chat.ChatSegmentDto;
import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.ImageUploadDto;
import com.superagent.api.chat.MessageDto;
import com.superagent.api.chat.SessionDetailDto;
import com.superagent.api.chat.SessionDto;
import com.superagent.api.chat.StreamEventDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
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
import com.superagent.web.attachment.ImageAttachmentStore;
import com.superagent.web.mcp.McpGrants;
import com.superagent.web.memory.AgentMemoryService;
import com.superagent.web.provider.ProviderService;
import com.superagent.web.skill.SkillService;
import com.superagent.web.settings.SettingsService;
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
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final Map<String, Cancellation> activeRuns = new ConcurrentHashMap<>();
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
            List<AgentRunner> agentRunners,
            McpGrants mcpGrants,
            @Value("${superagent.agent.workspace-dir:./data/workspaces}") String workspaceDir,
            @Value("${superagent.agent.default-model:}") String defaultModel,
            @Value("${superagent.agent.allowed-tools:Read,Glob,Grep,LS}") String allowedTools,
            @Value("${superagent.agent.skip-permissions:false}") boolean skipPermissions,
            @Value("${superagent.chat.sse-timeout-ms:600000}") long sseTimeoutMs,
            @Value("${superagent.mcp.enabled-in-chat:true}") boolean mcpInChat) {
        this.store = store;
        this.agentStore = agentStore;
        this.settingsService = settingsService;
        this.providerService = providerService;
        this.images = images;
        this.skills = skills;
        this.memory = memory;
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
            tools.addAll(McpGrants.INBOX_TOOLS);
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
            current = startSession(agent, current == null ? null : current.workspaceDir());
            log.info("agent {} starts session {} on model {}", agent.id(), current.id(), model);
        }
        memory.recordModel(agent, model);
        return current;
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
                    .filter(a -> a.owner() == null || a.owner().equals(user))
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
                agent == null ? List.of() : skills.forAgent(agent.id()), mcpGrants != null);
        String systemPrompt = character == null ? skillIndex
                : skillIndex == null ? character : character + "\n\n" + skillIndex;

        // Images are stored under the user message's id before it's appended, so a bad upload stores nothing.
        String userMessageId = UUID.randomUUID().toString();
        List<ImageAttachment> attached = images.save(sessionId, userMessageId, uploads);
        String text = content == null ? "" : content;
        store.appendMessage(new ChatMessage(userMessageId, sessionId, MessageRole.USER, text, Instant.now()));
        String prompt = text.isBlank() && !attached.isEmpty() ? ImageAttachmentStore.IMAGE_ONLY_PROMPT : text;

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        String assistantMessageId = UUID.randomUUID().toString();
        Cancellation cancellation = new Cancellation();
        activeRuns.put(sessionId, cancellation);

        ChatSession turn = session;
        String agentMemory = agent == null ? null : memory.read(agent);
        executor.execute(() -> runTurn(turn, agentType, runner, model, systemPrompt, agentMemory, prompt, attached, user,
                assistantMessageId, emitter, cancellation));
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

    private void runTurn(ChatSession session, AgentType agentType, AgentRunner runner, String model,
                         String systemPrompt, String agentMemory, String prompt, List<ImageAttachment> attached,
                         String user,
                         String assistantMessageId, SseEmitter emitter, Cancellation cancellation) {
        StringBuilder textAccumulator = new StringBuilder();
        AtomicReference<String> resultText = new AtomicReference<>();
        String agentSessionId = agentSessionIdFor(session, agentType);
        AtomicReference<String> capturedAgentSessionId = new AtomicReference<>(agentSessionId);

        AgentEventSink sink = event -> {
            AgentEvent stamped = new AgentEvent(session.id(), assistantMessageId, event.seq(),
                    event.type(), event.payload(), event.createdAt());
            store.appendEvent(stamped);
            send(emitter, new StreamEventDto(event.type().name().toLowerCase(), event.seq(), event.payload()));

            switch (event.type()) {
                case TEXT -> textAccumulator.append(event.payload());
                case RESULT -> resultText.set(event.payload());
                case SYSTEM -> {
                    if (event.payload() != null && !event.payload().isBlank()) {
                        capturedAgentSessionId.set(event.payload());
                    }
                }
                default -> { /* thinking / tool_use / error already forwarded */ }
            }
        };

        McpGrants.Grant grant = mcpGrants == null ? null : mcpGrants.openChat(user, session.agentId(), session.workspaceDir());
        try {
            AgentRunRequest request = new AgentRunRequest(
                    session.id(), agentSessionId, session.workspaceDir(), prompt, model, allowedTools, skipPermissions,
                    grant == null ? null : mcpGrants.mcpConfig(grant), systemPrompt, attached, agentMemory);
            runner.run(request, sink, cancellation);

            boolean stopped = cancellation.isCancelled();
            String finalText = resultText.get() != null ? resultText.get() : textAccumulator.toString();
            store.appendMessage(new ChatMessage(assistantMessageId, session.id(),
                    MessageRole.ASSISTANT, finalText, Instant.now()));
            persistSessionUpdate(session, agentType, capturedAgentSessionId.get(), prompt);

            if (stopped) {
                send(emitter, new StreamEventDto("stopped", -1, finalText));
            }
            send(emitter, new StreamEventDto("done", -1, finalText));
            emitter.complete();
        } catch (Exception e) {
            log.error("turn failed for session {}", session.id(), e);
            String detail = e.getMessage() != null ? e.getMessage() : "agent run failed";
            send(emitter, new StreamEventDto("error", -1, detail));
            emitter.complete();
        } finally {
            activeRuns.remove(session.id(), cancellation);
            if (grant != null) {
                mcpGrants.close(grant);
            }
        }
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
        return new MessageDto(m.id(), m.role().name().toLowerCase(), m.content(), m.createdAt(), urls);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
