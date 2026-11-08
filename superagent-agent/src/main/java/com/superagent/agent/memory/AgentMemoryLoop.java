package com.superagent.agent.memory;

import com.superagent.platform.tenant.CompanyService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background job that distils agents' conversations into their memory every {@code interval-minutes}
 * (default 5). Runs are one at a time on this thread, which also bounds what memory costs.
 */
@Component
public class AgentMemoryLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLoop.class);

    private final AgentMemoryService service;
    private final boolean enabled;
    private final long intervalMinutes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-memory");
        t.setDaemon(true);
        return t;
    });

    private final CompanyService companies;

    public AgentMemoryLoop(CompanyService companies, AgentMemoryService service,
                           @Value("${superagent.memory.enabled:true}") boolean enabled,
                           @Value("${superagent.memory.interval-minutes:5}") long intervalMinutes) {
        this.companies = companies;
        this.service = service;
        this.enabled = enabled;
        this.intervalMinutes = Math.max(1, intervalMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) {
            log.info("agent memory disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> companies.forEach(this::tick), intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("agent memory job started (every {} min)", intervalMinutes);
    }

    private void tick() {
        try {
            service.consolidateDue(Instant.now());
        } catch (RuntimeException e) {
            // A scheduled executor silently stops after an uncaught exception; keep the job alive.
            log.warn("agent memory tick failed (will retry): {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
