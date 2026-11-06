package com.superagent.agent.memory;

import com.superagent.platform.provider.CredentialService;
import com.superagent.agent.log.AgentLogService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentMemoryState;
import com.superagent.core.agent.AgentMemoryStateStore;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.chat.AgentEventSink;
import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.Cancellation;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.SessionStatus;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Agents' long-term memory: one Markdown document per agent, kept with the agent; its owner can read and edit it
 * (members can read it), from the agent's side panel. A background job
 * ({@link AgentMemoryLoop}) distils new conversation into it with the system default model; a session
 * that starts fresh (first turn, new model) gets it in its system prompt — see {@link AgentRunRequest}.
 *
 * <p>Memory belongs to the agent, not to a session: it spans every session the agent has had, so it is
 * what carries over when a model change starts a new one. Each run re-reads the document, so the user's
 * own edits are kept. Also tracks the model of the agent's current session ({@link #lastModel}).
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    /** {@link #recordModel} key for "the CLI's own default model", so it isn't confused with "never recorded". */
    private static final String CLI_DEFAULT = "-";

    private final AgentStore agents;
    private final ChatStore chats;
    private final AgentMemoryStateStore states;
    private final ProviderService providers;
    private final SettingsService settings;
    private final AgentLogService runLog;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);

    private CredentialService credentials; // null in tests: runs use the server's environment

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCredentials(CredentialService credentials) {
        this.credentials = credentials;
    }
    private final int minMessages;
    private final Duration settle;
    private final Duration idle;
    private final int perTick;
    private final ZoneId zone;
    /** agentId → don't retry before; a failed run backs off instead of burning the model every tick. */
    private final Map<String, Instant> backoff = new ConcurrentHashMap<>();
    /** Agents being distilled right now, so the periodic job and an on-demand run never overlap. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService soon = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-memory-now");
        t.setDaemon(true);
        return t;
    });
    private final int failureLimit;
    private final int recentMessages;

    public AgentMemoryService(AgentStore agents, ChatStore chats, AgentMemoryStateStore states,
                              ProviderService providers, SettingsService settings, List<AgentRunner> agentRunners,
                              @Value("${superagent.memory.min-messages:10}") int minMessages,
                              @Value("${superagent.memory.settle-minutes:2}") long settleMinutes,
                              @Value("${superagent.memory.idle-minutes:30}") long idleMinutes,
                              @Value("${superagent.memory.agents-per-tick:3}") int perTick,
                              @Value("${superagent.memory.timezone:Asia/Shanghai}") String timezone,
                              @Value("${superagent.agent.session-failure-limit:10}") int failureLimit,
                              @Value("${superagent.memory.recent-messages:10}") int recentMessages,
                              AgentLogService runLog) {
        this.agents = agents;
        this.chats = chats;
        this.states = states;
        this.providers = providers;
        this.settings = settings;
        for (AgentRunner r : agentRunners) {
            runners.put(r.type(), r);
        }
        this.minMessages = Math.max(1, minMessages);
        this.settle = Duration.ofMinutes(Math.max(0, settleMinutes));
        this.idle = Duration.ofMinutes(Math.max(settleMinutes, idleMinutes));
        this.perTick = Math.max(1, perTick);
        this.zone = ZoneId.of(timezone);
        this.failureLimit = Math.max(1, failureLimit);
        this.recentMessages = Math.max(0, recentMessages);
        this.runLog = runLog;
    }

    // --- reading, for a fresh session ---

    /**
     * What a fresh session starts from: the long-term memory plus the last few messages verbatim — the
     * part not distilled yet — so nothing between the last distillation and the switch is lost. Messages
     * in {@code exclude} (the one being sent now) are left out. Null when there is nothing at all.
     */
    public String context(Agent agent, Collection<String> exclude) {
        String memory = read(agent);
        List<ChatMessage> all = messages(agent.id()).stream()
                .filter(m -> exclude == null || !exclude.contains(m.id())).toList();
        List<ChatMessage> recent = all.subList(Math.max(0, all.size() - recentMessages), all.size());
        String transcript = recent.isEmpty() ? "" : AgentMemoryRules.transcript(recent, zone);
        if (memory == null && transcript.isBlank()) {
            return null;
        }
        return (memory == null ? "" : memory.strip())
                + (transcript.isBlank() ? "" : (memory == null ? "" : "\n\n")
                + "## 最近的对话（原文，最近 " + recent.size() + " 条）\n" + transcript);
    }

    // --- session health ---

    /**
     * Counts a failed turn against the agent's current session. Network-type failures don't count. Returns
     * true when the session has now failed {@code session-failure-limit} times in a row: the caller moves the
     * agent to a fresh session (and the count starts over).
     */
    public boolean recordFailure(Agent agent, String error) {
        if (SessionHealth.isTransient(error)) {
            return false;
        }
        AgentMemoryState s = state(agent);
        int failures = s.sessionFailures() + 1;
        boolean giveUp = failures >= failureLimit;
        states.save(s.withSessionFailures(giveUp ? 0 : failures));
        if (giveUp) {
            log.warn("agent {}: session failed {} times in a row; moving to a fresh session", agent.id(), failures);
        }
        return giveUp;
    }

    /** Consecutive counted failures of the agent's current session. */
    public int failureCount(String agentId) {
        return states.find(agentId).map(AgentMemoryState::sessionFailures).orElse(0);
    }

    public int failureLimit() {
        return failureLimit;
    }

    /** False for network-type errors, which never count against a session. */
    public boolean countsAgainstSession(String error) {
        return !SessionHealth.isTransient(error);
    }

    public void recordSuccess(Agent agent) {
        states.find(agent.id()).filter(s -> s.sessionFailures() > 0)
                .ifPresent(s -> states.save(s.withSessionFailures(0)));
    }

    /** The agent's memory document, or null when nothing worth remembering has been written yet. */
    public String read(Agent agent) {
        return states.find(agent.id()).map(AgentMemoryState::memory).filter(s -> !s.isBlank()).orElse(null);
    }

    /** When the memory last changed (by distilling or by its owner), or null without one. */
    public Instant updatedAt(Agent agent) {
        return read(agent) == null ? null : states.find(agent.id()).map(AgentMemoryState::updatedAt).orElse(null);
    }

    /** True while a distilling run is going for the agent. */
    public boolean distilling(String agentId) {
        return inFlight.contains(agentId);
    }

    /** The owner's own edit; blank clears the memory. The next distilling builds on what they left. */
    public void edit(Agent agent, String memory, String by) {
        String text = memory == null || memory.isBlank() ? null : memory.strip();
        if (text != null && text.length() > AgentMemoryRules.MAX_CHARS) {
            throw new IllegalArgumentException("记忆不能超过 " + AgentMemoryRules.MAX_CHARS + " 字");
        }
        states.save(state(agent).withMemory(text));
        runLog.info(agent, AgentLogService.MEMORY, text == null ? by + " 清空了记忆" : by + " 编辑了记忆", null);
    }

    /** Distils now whatever isn't in memory yet; false when there's nothing new or a run is already going. */
    public boolean distillNow(Agent agent) {
        if (undistilled(agent).isEmpty() || inFlight.contains(agent.id())) {
            return false;
        }
        consolidateSoon(agent);
        return true;
    }

    // --- the model of the current session ---

    /** The model the agent's current session talks to (null = unknown: never recorded). */
    public String lastModel(String agentId) {
        return states.find(agentId).map(AgentMemoryState::lastModel).map(m -> CLI_DEFAULT.equals(m) ? "" : m).orElse(null);
    }

    public void recordModel(Agent agent, String model) {
        String key = model == null || model.isBlank() ? CLI_DEFAULT : model;
        AgentMemoryState s = state(agent);
        if (!key.equals(s.lastModel())) {
            states.save(s.withLastModel(key));
        }
    }

    /** True when {@code model} differs from the model the current session was started with. */
    public boolean modelChanged(String agentId, String model) {
        String last = lastModel(agentId);
        return last != null && !last.equals(model == null ? "" : model);
    }

    // --- forgetting ---

    /** Deletes the agent's memory and its bookkeeping (when the agent itself is deleted). */
    public void forget(Agent agent) {
        states.delete(agent.id());
        backoff.remove(agent.id());
    }

    // --- distilling (driven by AgentMemoryLoop) ---

    /** Distils memory for agents whose conversation has settled; at most {@code agents-per-tick} per call. */
    int consolidateDue(Instant now) {
        int done = 0;
        for (Agent agent : agents.list()) {
            if (done >= perTick) {
                break;
            }
            if (agent.status() == AgentStatus.DELETED || agent.owner() == null
                    || backoff.getOrDefault(agent.id(), Instant.MIN).isAfter(now)) {
                continue;
            }
            List<ChatMessage> fresh = undistilled(agent);
            if (!AgentMemoryRules.due(fresh, now, minMessages, settle, idle) || !inFlight.add(agent.id())) {
                continue;
            }
            try {
                consolidate(agent, fresh);
            } finally {
                inFlight.remove(agent.id());
            }
            done++;
        }
        return done;
    }

    /**
     * Distils whatever isn't in memory yet, now and in the background, without waiting for the usual
     * thresholds — called when the agent moves to a fresh session.
     */
    public void consolidateSoon(Agent agent) {
        soon.execute(() -> {
            List<ChatMessage> fresh = undistilled(agent);
            if (fresh.isEmpty() || !inFlight.add(agent.id())) {
                return;
            }
            try {
                consolidate(agent, fresh);
            } catch (RuntimeException e) {
                log.warn("on-demand memory run for agent {} failed: {}", agent.id(), e.getMessage());
            } finally {
                inFlight.remove(agent.id());
            }
        });
    }

    private List<ChatMessage> undistilled(Agent agent) {
        Instant cursor = states.find(agent.id()).map(AgentMemoryState::summarizedUntil).orElse(null);
        return messages(agent.id()).stream().filter(m -> cursor == null || m.createdAt().isAfter(cursor)).toList();
    }

    private void consolidate(Agent agent, List<ChatMessage> fresh) {
        Instant until = fresh.get(fresh.size() - 1).createdAt();
        String current = read(agent);
        String answer;
        try {
            answer = summarize(AgentMemoryRules.prompt(agent.name(), current,
                    AgentMemoryRules.transcript(fresh, zone)));
        } catch (RuntimeException e) {
            log.warn("memory run for agent {} failed, retrying later: {}", agent.id(), e.getMessage());
            runLog.warn(agent, AgentLogService.MEMORY, "记忆沉淀失败，稍后重试", e.getMessage());
            backoff.put(agent.id(), Instant.now().plus(idle));
            return;
        }
        AgentMemoryRules.Outcome outcome = AgentMemoryRules.parse(answer);
        switch (outcome.kind()) {
            case INVALID -> {
                // Never overwrite memory with something that isn't a memory document; try again later.
                log.warn("memory run for agent {} returned no usable document; retrying later", agent.id());
                runLog.warn(agent, AgentLogService.MEMORY, "记忆沉淀没有得到有效的记忆文档，未写入，稍后重试", null);
                backoff.put(agent.id(), Instant.now().plus(idle));
                return;
            }
            case NO_CHANGE -> {
                log.info("memory of agent {}: nothing worth keeping in {} message(s)", agent.id(), fresh.size());
                runLog.info(agent, AgentLogService.MEMORY,
                        "沉淀了 " + fresh.size() + " 条消息：没有值得长期记住的内容，记忆未改动", null);
            }
            case UPDATED -> {
                write(agent, outcome.memory());
                log.info("memory of agent {} updated from {} message(s)", agent.id(), fresh.size());
                runLog.info(agent, AgentLogService.MEMORY, "记忆已更新（" + (current == null ? 0 : current.length())
                        + " → " + outcome.memory().length() + " 字，来自 " + fresh.size() + " 条消息）", null);
            }
        }
        states.save(state(agent).summarizedTo(until));
        backoff.remove(agent.id());
    }

    private com.superagent.core.provider.ModelRouter modelRouter; // null in tests; off unless the admin turns tiers on

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setModelRouter(com.superagent.core.provider.ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    private boolean tiersOn() {
        return modelRouter != null && modelRouter.enabled();
    }

    /** One run of the system default model (with tiers on, the fast tier), no tools, in a scratch directory; returns its final text. */
    private String summarize(String prompt) {
        String model = tiersOn() ? modelRouter.background().map(com.superagent.core.provider.ModelRouter.ModelChoice::model)
                .orElse(null) : settings.agentDefaultModel();
        AgentType type = providers.cliFor(model).map(AgentType::fromString).filter(runners::containsKey)
                .orElse(AgentType.CLAUDE);
        AgentRunner runner = runners.get(type);
        if (runner == null) {
            throw new IllegalStateException("No runner for " + type);
        }
        Path scratch;
        try {
            scratch = Files.createTempDirectory("sa-memory-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder text = new StringBuilder();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        AgentEventSink sink = event -> {
            switch (event.type()) {
                case TEXT -> text.append(event.payload());
                case RESULT -> result.set(event.payload());
                case ERROR -> error.set(event.payload());
                default -> { /* no tools here; nothing else to keep */ }
            }
        };
        try {
            runner.run(new AgentRunRequest(UUID.randomUUID().toString(), null, scratch.toString(), prompt, model,
                    List.of(), false, null, null, List.of(), null,
                    credentials == null ? null : credentials.current(type.cliName()).credential()), sink, new Cancellation());
        } finally {
            deleteQuietly(scratch);
        }
        String out = result.get() != null ? result.get() : text.toString();
        if (out.isBlank() && error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return out;
    }

    private void write(Agent agent, String memory) {
        states.save(state(agent).withMemory(memory));
    }

    /** Every message of every session the agent has had, oldest first. */
    private List<ChatMessage> messages(String agentId) {
        return chats.listSessionsByAgent(agentId).stream()
                .filter(s -> s.status() != SessionStatus.DELETED)
                .map(ChatSession::id)
                .flatMap(id -> chats.listMessages(id).stream())
                .sorted(Comparator.comparing(ChatMessage::createdAt))
                .toList();
    }

    private AgentMemoryState state(Agent agent) {
        return states.find(agent.id()).orElseGet(() ->
                new AgentMemoryState(agent.id(), agent.owner(), null, null, 0, null, Instant.now()));
    }

    @PreDestroy
    void shutdown() {
        soon.shutdownNow();
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp dir is the OS temp cleaner's job
        }
    }
}
