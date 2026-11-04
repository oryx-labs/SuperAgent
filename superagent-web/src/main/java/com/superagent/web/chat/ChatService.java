package com.superagent.web.chat;

import com.superagent.api.chat.CreateSessionRequest;
import com.superagent.api.chat.MessageDto;
import com.superagent.api.chat.SessionDetailDto;
import com.superagent.api.chat.SessionDto;
import com.superagent.api.chat.StreamEventDto;
import com.superagent.core.agent.Agent;
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
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.web.provider.ProviderService;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private final String defaultModel;
    private final List<String> allowedTools;
    private final boolean skipPermissions;
    private final long sseTimeoutMs;
    private final String mcpConfig;

    /** Task tools exposed by SuperAgent's own MCP server, allowlisted so the chat agent may call them. */
    private static final List<String> SUPERAGENT_MCP_TOOLS = List.of(
            "mcp__superagent__create_task", "mcp__superagent__list_tasks", "mcp__superagent__get_task",
            "mcp__superagent__send_task_message", "mcp__superagent__stop_task", "mcp__superagent__delete_task");

    public ChatService(
            ChatStore store,
            AgentStore agentStore,
            SettingsService settingsService,
            ProviderService providerService,
            List<AgentRunner> agentRunners,
            com.superagent.web.mcp.McpToken mcpToken,
            @Value("${superagent.agent.workspace-dir:./data/workspaces}") String workspaceDir,
            @Value("${superagent.agent.default-model:}") String defaultModel,
            @Value("${superagent.agent.allowed-tools:Read,Glob,Grep,LS}") String allowedTools,
            @Value("${superagent.agent.skip-permissions:false}") boolean skipPermissions,
            @Value("${superagent.chat.sse-timeout-ms:600000}") long sseTimeoutMs,
            @Value("${superagent.mcp.enabled-in-chat:true}") boolean mcpInChat,
            @Value("${superagent.mcp.self-url:http://127.0.0.1:8080/mcp}") String mcpSelfUrl) {
        this.store = store;
        this.agentStore = agentStore;
        this.settingsService = settingsService;
        this.providerService = providerService;
        for (AgentRunner runner : agentRunners) {
            this.runners.put(runner.type(), runner);
        }
        this.workspaceRoot = Path.of(workspaceDir).toAbsolutePath().normalize();
        this.defaultModel = defaultModel == null || defaultModel.isBlank() ? null : defaultModel.trim();
        List<String> tools = new ArrayList<>(parseTools(allowedTools));
        this.skipPermissions = skipPermissions;
        this.sseTimeoutMs = sseTimeoutMs;
        if (mcpInChat && mcpSelfUrl != null && !mcpSelfUrl.isBlank()) {
            // Expose SuperAgent's own MCP so the chat agent can create/steer tasks as tools.
            this.mcpConfig = "{\"mcpServers\":{\"superagent\":{\"type\":\"http\",\"url\":\""
                    + mcpSelfUrl.trim() + "\",\"headers\":{\"X-MCP-Token\":\"" + mcpToken.value() + "\"}}}}";
            tools.addAll(SUPERAGENT_MCP_TOOLS);
        } else {
            this.mcpConfig = null;
        }
        this.allowedTools = List.copyOf(tools);
        log.info("ChatService ready: runners={}, workspaceRoot={}, model={}, skipPermissions={}, superagentMcp={}",
                runners.keySet(), workspaceRoot, this.defaultModel, skipPermissions, mcpConfig != null);
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

    /** The id of the agent's owning session (phase 1 is 1:1), or null if it has none. */
    public String primarySessionId(String agentId) {
        return store.listSessionsByAgent(agentId).stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .map(ChatSession::id)
                .findFirst().orElse(null);
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
                .map(ChatService::toMessageDto).toList();
        return new SessionDetailDto(toDto(session), messages);
    }

    // --- sending a message (streamed) ---

    public SseEmitter sendMessage(String sessionId, String content) {
        ChatSession session = store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        Agent agent = session.agentId() == null || session.agentId().isBlank() ? null
                : agentStore.find(session.agentId()).orElse(null);
        String model = resolveModel(agent);
        // The model decides the CLI (a Codex model must run under codex); unknown models keep the session's own.
        AgentType agentType = providerService.cliFor(model).map(AgentType::fromString)
                .filter(runners::containsKey).orElse(session.agentType());
        AgentRunner runner = runners.get(agentType);
        if (runner == null) {
            throw new IllegalStateException("No runner for agent type " + agentType);
        }
        String systemPrompt = agent != null && agent.persona() != null && !agent.persona().isBlank()
                ? agent.persona().trim() : null;

        Instant now = Instant.now();
        store.appendMessage(new ChatMessage(UUID.randomUUID().toString(), sessionId,
                MessageRole.USER, content, now));

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        String assistantMessageId = UUID.randomUUID().toString();
        Cancellation cancellation = new Cancellation();
        activeRuns.put(sessionId, cancellation);

        executor.execute(() -> runTurn(session, agentType, runner, model, systemPrompt, content,
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

    /** Stops the in-flight turn for a session, if any. */
    public void stopGeneration(String sessionId) {
        Cancellation cancellation = activeRuns.get(sessionId);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }

    /** Edits a stored message's content in place (record only; does not re-run the agent). */
    public void editMessage(String sessionId, String messageId, String content) {
        store.findSession(sessionId).orElseThrow(() -> new SessionNotFoundException(sessionId));
        store.updateMessage(sessionId, messageId, content);
    }

    private void runTurn(ChatSession session, AgentType agentType, AgentRunner runner, String model,
                         String systemPrompt, String prompt,
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

        try {
            AgentRunRequest request = new AgentRunRequest(
                    session.id(), agentSessionId, session.workspaceDir(),
                    prompt, model, allowedTools, skipPermissions, mcpConfig, systemPrompt);
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

    private static MessageDto toMessageDto(ChatMessage m) {
        return new MessageDto(m.id(), m.role().name().toLowerCase(), m.content(), m.createdAt());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
