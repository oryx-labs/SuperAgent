package com.superagent.web.agentlog;

import com.superagent.api.agent.AgentLogDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentLogEntry;
import com.superagent.core.agent.AgentLogStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agents' run log: a readable trail of what happens beneath a conversation — sessions started or replaced,
 * each turn's outcome, failures and how they were counted, memory distilled, a SuperAgent's plans and
 * assignments — so the owner can see what an agent is doing and why. One line per turn, plus the events
 * that matter; tool-by-tool detail stays in the conversation's own events.
 *
 * <p>The log is a side channel: writing to it never fails the work being logged. Kept {@code retention-days}
 * (default 30) and at most {@code max-per-agent} lines (default 5000) per agent, pruned hourly.
 */
@Service
public class AgentLogService {

    private static final Logger log = LoggerFactory.getLogger(AgentLogService.class);

    public static final String SESSION = "session";
    public static final String TURN = "turn";
    public static final String MEMORY = "memory";
    public static final String SUPERAGENT = "superagent";

    private final AgentLogStore store;
    private final Duration retention;
    private final int maxPerAgent;
    private final ScheduledExecutorService pruner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-log-prune");
        t.setDaemon(true);
        return t;
    });

    public AgentLogService(AgentLogStore store,
                           @Value("${superagent.agent-log.retention-days:30}") long retentionDays,
                           @Value("${superagent.agent-log.max-per-agent:5000}") int maxPerAgent) {
        this.store = store;
        this.retention = Duration.ofDays(Math.max(1, retentionDays));
        this.maxPerAgent = Math.max(100, maxPerAgent);
    }

    public void info(Agent agent, String category, String summary, String detail) {
        write(agent, "info", category, summary, detail);
    }

    public void warn(Agent agent, String category, String summary, String detail) {
        write(agent, "warn", category, summary, detail);
    }

    public void error(Agent agent, String category, String summary, String detail) {
        write(agent, "error", category, summary, detail);
    }

    private void write(Agent agent, String level, String category, String summary, String detail) {
        if (agent == null) {
            return;
        }
        try {
            store.append(new AgentLogEntry(UUID.randomUUID().toString(), agent.id(), agent.owner(), Instant.now(),
                    level, category, summary, detail));
        } catch (RuntimeException e) {
            // The log watches the work; it must never break it.
            log.warn("could not write run log for agent {}: {}", agent.id(), e.getMessage());
        }
    }

    public List<AgentLogDto> list(String agentId, String category, int limit) {
        return store.list(agentId, category, Math.max(1, Math.min(limit, 1000))).stream()
                .map(e -> new AgentLogDto(e.id(), e.at(), e.level(), e.category(), e.summary(), e.detail()))
                .toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    void startPruning() {
        pruner.scheduleWithFixedDelay(this::prune, 1, 60, TimeUnit.MINUTES);
    }

    private void prune() {
        try {
            store.prune(Instant.now().minus(retention), maxPerAgent);
        } catch (RuntimeException e) {
            log.warn("run log pruning failed (will retry): {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        pruner.shutdownNow();
    }
}
