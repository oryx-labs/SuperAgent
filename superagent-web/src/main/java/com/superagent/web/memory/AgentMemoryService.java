package com.superagent.web.memory;

import com.superagent.api.knowledge.KnowledgeEntryDto;
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
import com.superagent.web.knowledge.KnowledgeService;
import com.superagent.web.provider.ProviderService;
import com.superagent.web.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Agents' long-term memory: one Markdown file per agent in its owner's knowledge base
 * ({@code Agent 记忆/<name>（<id>）.md}), so the user can read and edit it. A background job
 * ({@link AgentMemoryLoop}) distils new conversation into it with the system default model; a session
 * that starts fresh (first turn, new model) gets it in its system prompt — see {@link AgentRunRequest}.
 *
 * <p>Memory belongs to the agent, not to a session: it spans every session the agent has had, so it is
 * what carries over when a model change starts a new one. Each run re-reads the file, so the user's
 * own edits are kept. Also tracks the model of the agent's current session ({@link #lastModel}).
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);
    /** {@link #recordModel} key for "the CLI's own default model", so it isn't confused with "never recorded". */
    private static final String CLI_DEFAULT = "-";

    private final AgentStore agents;
    private final ChatStore chats;
    private final KnowledgeService knowledge;
    private final AgentMemoryStateStore states;
    private final ProviderService providers;
    private final SettingsService settings;
    private final Map<AgentType, AgentRunner> runners = new EnumMap<>(AgentType.class);
    private final int minMessages;
    private final Duration settle;
    private final Duration idle;
    private final int perTick;
    private final ZoneId zone;
    /** agentId → don't retry before; a failed run backs off instead of burning the model every tick. */
    private final Map<String, Instant> backoff = new ConcurrentHashMap<>();

    public AgentMemoryService(AgentStore agents, ChatStore chats, KnowledgeService knowledge, AgentMemoryStateStore states,
                              ProviderService providers, SettingsService settings, List<AgentRunner> agentRunners,
                              @Value("${superagent.memory.min-messages:10}") int minMessages,
                              @Value("${superagent.memory.settle-minutes:2}") long settleMinutes,
                              @Value("${superagent.memory.idle-minutes:30}") long idleMinutes,
                              @Value("${superagent.memory.agents-per-tick:3}") int perTick,
                              @Value("${superagent.memory.timezone:Asia/Shanghai}") String timezone) {
        this.agents = agents;
        this.chats = chats;
        this.knowledge = knowledge;
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
    }

    // --- reading, for a fresh session ---

    /** The agent's memory document, or null when nothing worth remembering has been written yet. */
    public String read(Agent agent) {
        return memoryFile(agent).map(e -> new String(knowledge.read(agent.owner(), e.path()).bytes(),
                StandardCharsets.UTF_8)).filter(s -> !s.isBlank()).orElse(null);
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

    /** Deletes the agent's memory file and its bookkeeping (when the agent itself is deleted). */
    public void forget(Agent agent) {
        memoryFile(agent).ifPresent(e -> knowledge.delete(agent.owner(), e.path()));
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
            Instant cursor = states.find(agent.id()).map(AgentMemoryState::summarizedUntil).orElse(null);
            List<ChatMessage> fresh = messages(agent.id()).stream()
                    .filter(m -> cursor == null || m.createdAt().isAfter(cursor)).toList();
            if (!AgentMemoryRules.due(fresh, now, minMessages, settle, idle)) {
                continue;
            }
            consolidate(agent, fresh);
            done++;
        }
        return done;
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
            backoff.put(agent.id(), Instant.now().plus(idle));
            return;
        }
        AgentMemoryRules.Outcome outcome = AgentMemoryRules.parse(answer);
        switch (outcome.kind()) {
            case INVALID -> {
                // Never overwrite memory with something that isn't a memory document; try again later.
                log.warn("memory run for agent {} returned no usable document; retrying later", agent.id());
                backoff.put(agent.id(), Instant.now().plus(idle));
                return;
            }
            case NO_CHANGE -> log.info("memory of agent {}: nothing worth keeping in {} message(s)", agent.id(), fresh.size());
            case UPDATED -> {
                write(agent, outcome.memory());
                log.info("memory of agent {} updated from {} message(s)", agent.id(), fresh.size());
            }
        }
        states.save(state(agent).summarizedTo(until));
        backoff.remove(agent.id());
    }

    /** One run of the system default model, no tools, in a scratch directory; returns its final text. */
    private String summarize(String prompt) {
        String model = settings.agentDefaultModel();
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
                    List.of(), false, null, null), sink, new Cancellation());
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
        String name = AgentMemoryRules.fileName(agent.name(), agent.id());
        Optional<KnowledgeEntryDto> old = memoryFile(agent);
        knowledge.writeText(agent.owner(), AgentMemoryRules.FOLDER, name, memory, true);
        // The agent was renamed since the last write: drop the file under its old name.
        old.filter(e -> !e.name().equals(name)).ifPresent(e -> knowledge.delete(agent.owner(), e.path()));
    }

    /** The agent's memory file, found by its id suffix so a rename doesn't lose it. */
    private Optional<KnowledgeEntryDto> memoryFile(Agent agent) {
        if (agent.owner() == null || !knowledge.folders(agent.owner()).contains(AgentMemoryRules.FOLDER)) {
            return Optional.empty();
        }
        String suffix = AgentMemoryRules.idSuffix(agent.id());
        return knowledge.list(agent.owner(), AgentMemoryRules.FOLDER).stream()
                .filter(e -> !e.dir() && e.name().endsWith(suffix)).findFirst();
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
                new AgentMemoryState(agent.id(), agent.owner(), null, null, Instant.now()));
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp dir is the OS temp cleaner's job
        }
    }
}
