package com.superagent.event;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxEventStore;
import com.superagent.core.inbox.InboxProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * The inbox: every received message is triaged and stored at once ({@link #ingest}), and processed
 * later in batches by priority — urgent (P0) right away, important (P1) hourly, routine (P2) in the
 * daily digest at the owner's chosen time. Receiving never waits on processing, so a slow model run
 * can't make a source drop messages.
 *
 * <p>Processing state lives in the DB (an event's {@code processed} flag, a source's last hourly /
 * digest run), so a restart neither loses nor repeats a window. Each owner's windows run one at a time.
 */
@Service
public class InboxService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);
    static final int BATCH_LIMIT = 300;
    private static final Duration HOURLY = Duration.ofHours(1);
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    private final InboxEventStore events;
    private final EventSourceStore sources;
    private final InboxProcessor processor;
    private final int retentionDays;
    private final Map<String, Object> ownerLocks = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedUntil = new ConcurrentHashMap<>();
    /** Per source type: pushes a short line to the owner (e.g. a Feishu DM). Registered by connectors. */
    private final Map<String, BiConsumer<EventSource, String>> notifiers = new ConcurrentHashMap<>();
    private final ExecutorService urgent = Executors.newFixedThreadPool(2, daemon("inbox-urgent"));
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(daemon("inbox-tick"));
    private volatile LocalDate lastCleanup;
    private volatile boolean running;

    public InboxService(InboxEventStore events, EventSourceStore sources, InboxProcessor processor,
                        @Value("${superagent.inbox.retention-days:30}") int retentionDays) {
        this.events = events;
        this.sources = sources;
        this.processor = processor;
        this.retentionDays = Math.max(1, retentionDays);
    }

    public void registerNotifier(String sourceType, BiConsumer<EventSource, String> notifier) {
        notifiers.put(sourceType, notifier);
    }

    /**
     * Triage and store one received message (priority/reason are filled in here). A redelivered
     * message is dropped. An urgent one is processed immediately on a background thread.
     */
    public InboxEvent ingest(EventSource source, InboxEvent e, boolean fromOwner, boolean fromBot) {
        TriageRules.Decision d = TriageRules.triage(source, e, fromOwner, fromBot);
        InboxEvent triaged = new InboxEvent(e.id(), source.owner(), e.source(), e.externalId(), e.chatId(), e.chatName(),
                e.chatType(), e.threadId(), e.senderId(), e.senderName(), e.text(), e.mentionsMe(), e.raw(),
                d.priority(), d.reason(), d.priority() == EventPriority.IGNORE, e.occurredAt(), Instant.now(), Instant.now());
        if (!events.insertIfAbsent(triaged)) {
            return null;
        }
        log.info("inbox {}: {} from {} in {} → {} ({})", source.owner(), triaged.source(), triaged.senderName(),
                triaged.chatName(), d.priority(), d.reason());
        if (d.priority() == EventPriority.P0 && running) {
            urgent.execute(() -> runWindow(source.owner(), source.type(), EventPriority.P0));
        }
        return triaged;
    }

    /** One scheduler pass over every enabled source; also retries urgent messages a failed run left behind. */
    void tick(Instant now) {
        for (String type : notifiersOrDefault()) {
            for (EventSource s : sources.listEnabled(type)) {
                try {
                    runWindow(s.owner(), type, EventPriority.P0);
                    if (s.lastHourlyAt() == null || !now.isBefore(s.lastHourlyAt().plus(HOURLY))) {
                        runWindow(s.owner(), type, EventPriority.P1);
                        update(s.owner(), type, cur -> cur.withRuns(cur.lastDigestDate(), now));
                    }
                    ZoneId zone = zone(s.timezone());
                    ZonedDateTime local = now.atZone(zone);
                    if (!local.toLocalTime().isBefore(digestTime(s.digestTime()))
                            && !local.toLocalDate().equals(s.lastDigestDate())) {
                        runWindow(s.owner(), type, EventPriority.P2);
                        update(s.owner(), type, cur -> cur.withRuns(local.toLocalDate(), cur.lastHourlyAt()));
                    }
                } catch (RuntimeException ex) {
                    log.warn("inbox tick failed for {}: {}", s.owner(), ex.getMessage());
                }
            }
        }
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (!today.equals(lastCleanup)) {
            lastCleanup = today;
            int n = events.deleteBefore(now.minus(Duration.ofDays(retentionDays)));
            if (n > 0) {
                log.info("inbox: removed {} messages older than {} days", n, retentionDays);
            }
        }
    }

    /**
     * Processes one owner's pending messages of one priority as a batch. Failures leave the messages
     * pending; the window is then skipped for a while so a broken model setup doesn't retry every minute.
     */
    void runWindow(String owner, String type, EventPriority window) {
        String key = owner + "/" + type + "/" + window;
        Instant blocked = failedUntil.get(key);
        if (blocked != null && Instant.now().isBefore(blocked)) {
            return;
        }
        synchronized (ownerLocks.computeIfAbsent(owner, k -> new Object())) {
            EventSource source = sources.find(owner, type).orElse(null);
            if (source == null || !source.enabled()) {
                return;
            }
            List<InboxEvent> batch = events.pending(owner, window, BATCH_LIMIT);
            if (batch.isEmpty()) {
                return;
            }
            InboxProcessor.Result result;
            try {
                result = processor.process(source, window, batch);
                failedUntil.remove(key);
            } catch (RuntimeException e) {
                failedUntil.put(key, Instant.now().plus(RETRY_AFTER_FAILURE));
                log.warn("inbox {} {} window failed ({} messages kept for retry): {}", owner, window, batch.size(),
                        e.getMessage());
                return;
            }
            batch.forEach(e -> events.update(e.processedNow()));
            log.info("inbox {} {} window: processed {} messages", owner, window, batch.size());
            BiConsumer<EventSource, String> notifier = notifiers.get(type);
            if (result != null && result.notifyText() != null && notifier != null) {
                try {
                    notifier.accept(source, result.notifyText());
                } catch (RuntimeException e) {
                    log.warn("inbox {}: could not push notification: {}", owner, e.getMessage());
                }
            }
        }
    }

    private Iterable<String> notifiersOrDefault() {
        return notifiers.isEmpty() ? List.of(EventSource.FEISHU) : notifiers.keySet();
    }

    private void update(String owner, String type, UnaryOperator<EventSource> change) {
        sources.find(owner, type).ifPresent(cur -> sources.save(change.apply(cur)));
    }

    static ZoneId zone(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    static LocalTime digestTime(String hhmm) {
        try {
            return hhmm == null || hhmm.isBlank() ? LocalTime.of(9, 0) : LocalTime.parse(hhmm);
        } catch (RuntimeException e) {
            return LocalTime.of(9, 0);
        }
    }

    // --- lifecycle ---

    @Override
    public void start() {
        running = true;
        ticker.scheduleWithFixedDelay(() -> {
            try {
                tick(Instant.now());
            } catch (RuntimeException e) {
                log.warn("inbox tick failed: {}", e.getMessage());
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        running = false;
        ticker.shutdownNow();
        urgent.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
