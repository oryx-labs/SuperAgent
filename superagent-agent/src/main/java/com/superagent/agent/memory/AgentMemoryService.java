package com.superagent.agent.memory;

import com.superagent.core.tenant.TenantContext;
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
    /** How many characters of the latest conversation a new session reads verbatim. */
    private final int recentMessages;
    private com.superagent.files.FileService files; // null in tests: no files carried over

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFiles(com.superagent.files.FileService files) {
        this.files = files;
    }

    /** How many of the agent's files a new session is told about, and how much of the newest text ones it reads. */
    private static final int CARRIED_FILES = 20;
    private static final int EXCERPTED_FILES = 3;
    private static final int EXCERPT_CHARS = 6_000;

    public AgentMemoryService(AgentStore agents, ChatStore chats, AgentMemoryStateStore states,
                              ProviderService providers, SettingsService settings, List<AgentRunner> agentRunners,
                              @Value("${superagent.memory.min-messages:10}") int minMessages,
                              @Value("${superagent.memory.settle-minutes:2}") long settleMinutes,
                              @Value("${superagent.memory.idle-minutes:30}") long idleMinutes,
                              @Value("${superagent.memory.agents-per-tick:3}") int perTick,
                              @Value("${superagent.memory.timezone:Asia/Shanghai}") String timezone,
                              @Value("${superagent.agent.session-failure-limit:10}") int failureLimit,
                              @Value("${superagent.memory.carry-chars:60000}") int recentMessages,
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
     * What a fresh session starts from: the long-term memory, the latest conversation verbatim (as much as the budget
     * holds, whole messages first), and the agent's files: what it made lately, the newest text ones read in part.
     * Most of what earlier work came to lives in those files, listed as far as {@code user} (who the turn is for) may see
     * them. Messages in {@code exclude} (the one being sent now) are left out. Null when there is nothing at all.
     */
    public String context(Agent agent, Collection<String> exclude, String user) {
        String memory = read(agent);
        List<ChatMessage> all = messages(agent.id()).stream()
                .filter(m -> exclude == null || !exclude.contains(m.id())).toList();
        String transcript = all.isEmpty() ? "" : AgentMemoryRules.carryOver(all, zone, recentMessages);
        String madeFiles = filesSection(agent, user);
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (memory != null && !memory.isBlank()) {
            parts.add(memory.strip());
        }
        if (!transcript.isBlank()) {
            parts.add("## 最近的对话（原文，从较早到最新；更早的对话用 chat_history_search / chat_history_read 查）\n" + transcript);
        }
        if (madeFiles != null) {
            parts.add(madeFiles);
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /** The agent's files, newest first, and the start of the newest text ones: where its earlier work ended up. */
    private String filesSection(Agent agent, String user) {
        if (files == null || user == null) {
            return null;
        }
        List<com.superagent.core.file.WorkFile> made = files.recentForAgent(agent.id(), user, CARRIED_FILES);
        if (made.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("## 你的文件（「文件」里这个 Agent 的目录，最近的在前）\n"
                + "之前任务和对话的成果大多在这些文件里。要用哪个，用 mcp__superagent__file_get 按 id 取到工作区再读。\n");
        java.time.format.DateTimeFormatter stamp = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (com.superagent.core.file.WorkFile f : made) {
            sb.append("- ").append(f.name()).append(" · id ").append(f.id()).append(" · ")
                    .append(stamp.format(f.updatedAt().atZone(zone))).append(" · ").append(originLabel(f))
                    .append(f.tags().isEmpty() ? "" : " · 标签 " + String.join("、", f.tags())).append('\n');
        }
        int shown = 0;
        for (com.superagent.core.file.WorkFile f : made) {
            if (shown >= EXCERPTED_FILES) {
                break;
            }
            String text = files.excerpt(f, user, EXCERPT_CHARS);
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append("\n### ").append(f.name()).append("（开头部分）\n").append(text.strip()).append('\n');
            shown++;
        }
        return sb.toString().stripTrailing();
    }

    private static String originLabel(com.superagent.core.file.WorkFile f) {
        return switch (f.origin() == null ? "" : f.origin()) {
            case com.superagent.core.file.WorkFile.ORIGIN_TASK -> "任务产出";
            case com.superagent.core.file.WorkFile.ORIGIN_CHAT -> "对话中保存";
            case com.superagent.core.file.WorkFile.ORIGIN_UPLOAD -> "用户上传";
            default -> "文件";
        };
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
        soon.execute(TenantContext.wrap(() -> {
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
        }));
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
            answer = summarize(agent, AgentMemoryRules.prompt(agent.name(), current,
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

    private com.superagent.core.usage.UsageRecorder usage; // null in tests: tokens aren't kept

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUsageRecorder(com.superagent.core.usage.UsageRecorder usage) {
        this.usage = usage;
    }

    private com.superagent.core.provider.ModelRouter modelRouter; // null in tests: the default model

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setModelRouter(com.superagent.core.provider.ModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }


    /** One run of the cheapest model that can run (else the default), no tools, in a scratch directory; returns its final text. */
    private String summarize(Agent agent, String prompt) {
        String model = (modelRouter == null ? java.util.Optional.<com.superagent.core.provider.ModelRouter.ModelChoice>empty()
                : modelRouter.cheapest()).map(com.superagent.core.provider.ModelRouter.ModelChoice::model)
                .orElseGet(settings::agentDefaultModel);
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
                    credentials == null ? null : credentials.current(type.cliName()).credential()),
                    // Keeping an agent's memory is part of using it: on its owner.
                    com.superagent.core.usage.UsageRecorder.tap(usage, new com.superagent.core.usage.UsageContext(agent.owner(),
                            com.superagent.core.usage.UsageContext.SOURCE_MEMORY, agent.id(), null, null, type.cliName()), sink),
                    new Cancellation());
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
