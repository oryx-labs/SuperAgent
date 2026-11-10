package com.superagent.platform.provider;

import com.superagent.core.provider.ModelProvider;
import com.superagent.core.provider.ModelProviderStore;
import com.superagent.core.tenant.TenantContext;
import com.superagent.platform.tenant.CompanyService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background refresh of managed provider status. Two cadences: the cheap local probe
 * (installed / version / auth) every {@code local-interval-seconds} (default 3s), and
 * the network work — latest published version and the model list — every
 * {@code latest-interval-seconds} (default 1h); the detail page can also refresh the
 * model list on demand. A third cadence, {@code infer-interval-seconds} (default 1h),
 * sends every listed model one real "你好" and records ok / error per model, since a
 * listed model isn't necessarily one this account can run.
 */
@Component
public class ProviderStatusChecker {

    private static final Logger log = LoggerFactory.getLogger(ProviderStatusChecker.class);

    private final ModelProviderStore store;
    private final ProviderService service;
    private final boolean enabled;
    private final long localSeconds;
    private final long latestSeconds;
    private final long inferSeconds;
    private final long credentialSeconds;
    private final CredentialService credentials;
    // One thread per cadence: the network refresh and the inference sweep (both slow) must not stall the
    // 3s local probe. ProviderService serializes the row writes, so the cadences can't clobber each other.
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "provider-checker");
        t.setDaemon(true);
        return t;
    });

    private final CompanyService companies;

    public ProviderStatusChecker(CompanyService companies,
            ModelProviderStore store,
            ProviderService service,
            @Value("${superagent.provider.checker.enabled:true}") boolean enabled,
            @Value("${superagent.provider.checker.local-interval-seconds:3}") long localSeconds,
            @Value("${superagent.provider.checker.latest-interval-seconds:3600}") long latestSeconds,
            @Value("${superagent.provider.checker.infer-interval-seconds:3600}") long inferSeconds,
            @Value("${superagent.provider.checker.credential-interval-seconds:600}") long credentialSeconds,
            CredentialService credentials) {
        this.companies = companies;
        this.store = store;
        this.service = service;
        this.enabled = enabled;
        this.localSeconds = Math.max(1, localSeconds);
        this.latestSeconds = Math.max(60, latestSeconds);
        this.inferSeconds = Math.max(60, inferSeconds);
        this.credentialSeconds = Math.max(60, credentialSeconds);
        this.credentials = credentials;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!enabled) {
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> companies.forEach(this::refreshLocal), 0, localSeconds, TimeUnit.SECONDS);
        // Startup: fetch the model list, then probe it right away — waiting a full hourly cycle would leave
        // every model "not checked" (or stale from before the restart) for an hour.
        scheduler.execute(() -> companies.forEach(() -> {
            refreshLatest();
            refreshInference();
        }));
        scheduler.scheduleWithFixedDelay(() -> companies.forEach(this::refreshLatest), latestSeconds, latestSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(() -> companies.forEach(this::refreshInference), inferSeconds, inferSeconds, TimeUnit.SECONDS);
        // Every key in the pools, failing ones included: a recovered higher-priority key takes over again.
        scheduler.scheduleWithFixedDelay(() -> companies.forEach(this::checkCredentials), 60, credentialSeconds, TimeUnit.SECONDS);
        log.info("provider checker started (local={}s, latest={}s, infer={}s)", localSeconds, latestSeconds, inferSeconds);
    }

    private void refreshLocal() {
        for (ModelProvider p : store.list()) { // one CLI's failure mustn't leave the others stale
            try {
                service.refreshLocal(p);
            } catch (RuntimeException e) {
                log.warn("provider {} local refresh failed (will retry): {}", p.providerKey(), e.getMessage());
            }
        }
    }

    private void refreshLatest() {
        for (ModelProvider p : store.list()) {
            try {
                service.refreshLatest(p);
                service.refreshLocal(p);
                service.refreshModels(p);
            } catch (RuntimeException e) {
                log.warn("provider {} network refresh failed (will retry): {}", p.providerKey(), e.getMessage());
            }
        }
    }

    /** After a sweep that couldn't reach a CLI's service: try again this soon rather than at the next hourly one. */
    private static final long RETRY_UNREACHABLE_SECONDS = 300;
    /** Companies with a retry scheduled: one company's unreachable CLI says nothing about another's keys. */
    private final java.util.Set<String> retryPending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void refreshInference() {
        boolean reached = true;
        try {
            for (ModelProvider p : store.list()) {
                reached &= service.refreshInference(p);
            }
        } catch (RuntimeException e) {
            log.warn("provider inference check failed (will retry): {}", e.getMessage());
        }
        String company = TenantContext.require();
        if (!reached && retryPending.add(company)) {
            log.info("provider inference check will retry in {}s", RETRY_UNREACHABLE_SECONDS);
            scheduler.schedule(TenantContext.wrap(() -> {
                retryPending.remove(company);
                refreshInference();
            }), RETRY_UNREACHABLE_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void checkCredentials() {
        try {
            credentials.checkAll();
        } catch (RuntimeException e) {
            log.warn("credential check failed (will retry): {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
