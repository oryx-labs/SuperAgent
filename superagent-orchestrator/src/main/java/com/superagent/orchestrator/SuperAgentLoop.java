package com.superagent.orchestrator;

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
 * The SuperAgents' always-on listener: every {@code tick-seconds} (default 3s) it reports finished
 * team tasks into the inboxes and starts a round for any inbox that has gone quiet. The tick only
 * looks; a model is called only when there is new input.
 */
@Component
public class SuperAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(SuperAgentLoop.class);

    private final SuperAgentService service;
    private final boolean enabled;
    private final long tickSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "superagent-loop");
        t.setDaemon(true);
        return t;
    });

    private final CompanyService companies;

    public SuperAgentLoop(CompanyService companies, SuperAgentService service,
                          @Value("${superagent.superagent.enabled:true}") boolean enabled,
                          @Value("${superagent.superagent.tick-seconds:3}") long tickSeconds) {
        this.companies = companies;
        this.service = service;
        this.enabled = enabled;
        this.tickSeconds = Math.max(1, tickSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) {
            log.info("superagent loop disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> companies.forEach(this::tick), tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("superagent loop started (every {}s)", tickSeconds);
    }

    private void tick() {
        try {
            service.tick(Instant.now());
        } catch (RuntimeException e) {
            // A scheduled executor silently stops after an uncaught exception; keep listening.
            log.warn("superagent tick failed (will retry): {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
