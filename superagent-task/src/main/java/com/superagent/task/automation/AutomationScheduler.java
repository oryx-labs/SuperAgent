package com.superagent.task.automation;

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
 * Ticks every {@code interval-seconds} (default 30s) and fires the automations that are due.
 * The first tick runs right after startup, so schedules missed while the server was down
 * get their one catch-up run then (see {@link AutomationService#fireDue}).
 */
@Component
public class AutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

    private final AutomationService service;
    private final boolean enabled;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "automation-scheduler");
        t.setDaemon(true);
        return t;
    });

    public AutomationScheduler(
            AutomationService service,
            @Value("${superagent.automation.scheduler.enabled:true}") boolean enabled,
            @Value("${superagent.automation.scheduler.interval-seconds:30}") long intervalSeconds) {
        this.service = service;
        this.enabled = enabled;
        this.intervalSeconds = Math.max(5, intervalSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) {
            log.info("automation scheduler disabled");
            return;
        }
        scheduler.scheduleWithFixedDelay(this::tick, 0, intervalSeconds, TimeUnit.SECONDS);
        log.info("automation scheduler started (every {}s)", intervalSeconds);
    }

    private void tick() {
        try {
            service.fireDue(Instant.now());
        } catch (RuntimeException e) {
            // A scheduled executor silently stops after an uncaught exception; keep ticking.
            log.warn("automation tick failed (will retry): {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
