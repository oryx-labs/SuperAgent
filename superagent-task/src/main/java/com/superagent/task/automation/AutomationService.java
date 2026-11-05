package com.superagent.task.automation;

import com.superagent.api.ApiPaths;
import com.superagent.api.automation.AutomationDetailDto;
import com.superagent.api.automation.AutomationDto;
import com.superagent.api.automation.AutomationRunDto;
import com.superagent.api.automation.SaveAutomationRequest;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.automation.Automation;
import com.superagent.core.automation.AutomationRun;
import com.superagent.core.automation.AutomationSchedule;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.automation.AutomationType;
import com.superagent.core.automation.OverlapPolicy;
import com.superagent.core.task.TaskStatus;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Automations: saved prompts that run as ordinary tasks — on a cron schedule, once at a set
 * time, or when an external system calls the trigger endpoint. Each firing goes through
 * {@link #fire}, which applies the overlap policy, starts a task owned by the automation's
 * owner and records the run.
 *
 * <p>The scheduler persists the next firing time <em>before</em> starting the task, so a crash
 * can drop one run but never repeat one; and the next time is computed from "now", so firings
 * missed while the server was down collapse into a single catch-up run.
 *
 * <p>Only the owner (or an admin) may change, run or delete an automation: it runs as its owner
 * and writes to their knowledge base. All mutations and firings share one lock.
 */
@Service
public class AutomationService {

    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private static final int RECENT_RUNS = 20;
    private static final int UPCOMING = 3;
    /** Automation runs have nobody to answer a confirmation, so the brief says so. */
    private static final String UNATTENDED_NOTE = "（这是由自动化触发的任务，执行时无人值守：不要停下来等待确认，"
            + "遇到不确定的地方请做出合理假设，并在最终总结中说明。）";
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    private final AutomationStore store;
    private final AutomationTasks tasks;
    private final ProviderService providers;
    private final SettingsService settings;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    public AutomationService(AutomationStore store, AutomationTasks tasks,
                             ProviderService providers, SettingsService settings) {
        this.store = store;
        this.tasks = tasks;
        this.providers = providers;
        this.settings = settings;
    }

    /** The caller's own automations (an admin sees everyone's): prompts can hold anything, so reads are scoped too. */
    public List<AutomationDto> list(String user, boolean admin) {
        return store.list().stream().filter(a -> admin || Objects.equals(a.owner(), user))
                .map(a -> toDto(a, null)).toList();
    }

    public AutomationDetailDto detail(String id, String user, boolean admin) {
        Automation a = requireVisible(id, user, admin);
        List<AutomationRunDto> runs = store.listRuns(id, RECENT_RUNS).stream().map(this::toRunDto).toList();
        return new AutomationDetailDto(toDto(a, null), runs, upcoming(a));
    }

    /** {@code source}: "manual" (the form) or "chat" (an agent's tool call). */
    public AutomationDto create(SaveAutomationRequest request, String owner, String source) {
        Instant now = Instant.now();
        Spec spec = validate(request, now, true, true);
        synchronized (lock) {
            String token = spec.type == AutomationType.API ? newToken() : null;
            Automation a = new Automation(UUID.randomUUID().toString(), spec.name, spec.prompt, spec.type, spec.cron,
                    spec.runAt, spec.timezone, spec.model, spec.overlap, spec.enabled, source, owner,
                    token == null ? null : hash(token), null, null, null, now, now);
            a = a.withNextRun(nextRun(a, now));
            store.save(a);
            log.info("automation {} ({}, {}) created by {} via {}", a.id(), a.name(), a.type(), owner, source);
            return toDto(a, token);
        }
    }

    public AutomationDto update(String id, SaveAutomationRequest request, String user, boolean admin) {
        Instant now = Instant.now();
        synchronized (lock) {
            Automation cur = requireEditable(id, user, admin);
            // A one-off's time only has to be in the future when it changes (or it's re-armed), not on every edit.
            boolean rearming = request.runAt() != null || (Boolean.TRUE.equals(request.enabled()) && !cur.enabled());
            // Likewise the model is only re-checked when it changes, so a rename still works if it went unhealthy.
            boolean modelChanged = request.model() != null && !request.model().strip().equals(nn(cur.model()));
            Spec spec = validate(merge(cur, request), now, rearming, modelChanged);
            String tokenHash = spec.type == AutomationType.API ? cur.apiTokenHash() : null;
            String token = null;
            if (spec.type == AutomationType.API && tokenHash == null) {
                token = newToken(); // switched to API: mint its first token
                tokenHash = hash(token);
            }
            Automation a = new Automation(cur.id(), spec.name, spec.prompt, spec.type, spec.cron, spec.runAt,
                    spec.timezone, spec.model, spec.overlap, spec.enabled, cur.source(), cur.owner(), tokenHash,
                    null, cur.lastRunAt(), cur.lastTaskId(), cur.createdAt(), now);
            a = a.withNextRun(nextRun(a, now));
            store.save(a);
            return toDto(a, token);
        }
    }

    public AutomationDto setEnabled(String id, boolean enabled, String user, boolean admin) {
        Instant now = Instant.now();
        synchronized (lock) {
            Automation cur = requireEditable(id, user, admin);
            if (enabled && cur.type() == AutomationType.ONCE && (cur.runAt() == null || !cur.runAt().isAfter(now))) {
                throw new IllegalArgumentException("This one-time automation's time has passed; set a new time to enable it");
            }
            Automation on = cur.withEnabled(enabled, null);
            Automation a = on.withNextRun(nextRun(on, now));
            store.save(a);
            return toDto(a, null);
        }
    }

    public void delete(String id, String user, boolean admin) {
        synchronized (lock) {
            requireEditable(id, user, admin);
            store.delete(id);
        }
    }

    /** "Run now": fires immediately, whatever the type or schedule; the schedule itself is unchanged. */
    public AutomationRunDto runNow(String id, String user, boolean admin) {
        synchronized (lock) {
            return toRunDto(fire(requireEditable(id, user, admin), "manual", null));
        }
    }

    /** External trigger: only an API automation, only with its token, and only while enabled. */
    public AutomationRunDto trigger(String id, String token, String input) {
        synchronized (lock) {
            Automation a = store.find(id).orElse(null);
            // One answer for "no such automation" and "wrong token", so ids can't be probed.
            if (a == null || a.type() != AutomationType.API || a.apiTokenHash() == null || token == null
                    || !MessageDigest.isEqual(a.apiTokenHash().getBytes(StandardCharsets.UTF_8),
                    hash(token).getBytes(StandardCharsets.UTF_8))) {
                throw new AutomationForbiddenException("Invalid automation id or token");
            }
            if (!a.enabled()) {
                throw new IllegalArgumentException("This automation is paused");
            }
            return toRunDto(fire(a, "api", input));
        }
    }

    /** Mints a new API token; the old one stops working at once. */
    public AutomationDto regenerateToken(String id, String user, boolean admin) {
        synchronized (lock) {
            Automation cur = requireEditable(id, user, admin);
            if (cur.type() != AutomationType.API) {
                throw new IllegalArgumentException("Only API automations have a token");
            }
            String token = newToken();
            Automation a = cur.withApiTokenHash(hash(token));
            store.save(a);
            return toDto(a, token);
        }
    }

    /** Next firings of a draft schedule (for the form's preview); throws on an invalid one. */
    public List<Instant> preview(SaveAutomationRequest request) {
        SaveAutomationRequest draft = new SaveAutomationRequest(request.name(),
                request.prompt() == null || request.prompt().isBlank() ? "preview" : request.prompt(),
                request.type(), request.cron(), request.runAt(), request.timezone(), null, null, true);
        Spec spec = validate(draft, Instant.now(), true, false);
        return upcomingFor(spec.type, spec.cron, spec.runAt, spec.timezone);
    }

    // --- scheduling (driven by AutomationScheduler) ---

    /** Fires every enabled schedule whose time has come. Returns how many fired. */
    int fireDue(Instant now) {
        int fired = 0;
        synchronized (lock) {
            for (Automation a : store.list()) {
                if (!a.enabled() || a.nextRunAt() == null || a.nextRunAt().isAfter(now)) {
                    continue;
                }
                // Advance (or retire) the schedule first: at most once, and missed firings collapse into this one.
                Automation advanced = a.type() == AutomationType.ONCE
                        ? a.withEnabled(false, null)
                        : a.withNextRun(AutomationSchedule.nextCron(a.cron(), a.timezone(), now));
                store.save(advanced);
                fire(advanced, "schedule", null);
                fired++;
            }
        }
        return fired;
    }

    /** Starts one run (or records why it was skipped). Call under the lock. */
    private AutomationRun fire(Automation a, String trigger, String input) {
        Instant now = Instant.now();
        String taskId = null;
        String skipped = null;
        if (a.overlap() == OverlapPolicy.SKIP && stillRunning(a.lastTaskId())) {
            skipped = "上一次运行尚未结束（重叠策略：跳过）";
        } else {
            try {
                taskId = tasks.start(taskRequest(a, input, now), a.owner());
            } catch (RuntimeException e) {
                // A failed start is part of this run's outcome: keep it in the history instead of losing it in a log.
                log.warn("automation {} could not start its task: {}", a.id(), e.getMessage());
                skipped = "启动任务失败：" + e.getMessage();
            }
        }
        AutomationRun run = new AutomationRun(UUID.randomUUID().toString(), a.id(), trigger, taskId, skipped, now);
        store.appendRun(run);
        store.save(a.fired(now, taskId));
        log.info("automation {} fired ({}): {}", a.id(), trigger, taskId != null ? "task " + taskId : skipped);
        return run;
    }

    private CreateTaskRequest taskRequest(Automation a, String input, Instant now) {
        String model = a.model() != null ? a.model() : settings.agentDefaultModel();
        String agentType = providers.cliFor(model).orElse(null);
        String title = a.name() + " · " + TITLE_TIME.format(now.atZone(AutomationSchedule.zone(a.timezone())));
        String brief = a.prompt()
                + (input == null || input.isBlank() ? "" : "\n\n## 本次输入\n" + input.strip())
                + "\n\n" + UNATTENDED_NOTE;
        return new CreateTaskRequest(title, brief, "automation", null, null, null, model, agentType, null);
    }

    private boolean stillRunning(String taskId) {
        if (taskId == null) {
            return false;
        }
        // Only a task actually executing counts: one waiting on a confirmation must not block every later run.
        return tasks.status(taskId).map(s -> s == TaskStatus.PENDING || s == TaskStatus.RUNNING).orElse(false);
    }

    private static Instant nextRun(Automation a, Instant now) {
        if (!a.enabled()) {
            return null;
        }
        return switch (a.type()) {
            case RECURRING -> AutomationSchedule.nextCron(a.cron(), a.timezone(), now);
            case ONCE -> a.runAt();
            case API -> null;
        };
    }

    private List<Instant> upcoming(Automation a) {
        return a.enabled() ? upcomingFor(a.type(), a.cron(), a.runAt(), a.timezone()) : List.of();
    }

    private static List<Instant> upcomingFor(AutomationType type, String cron, Instant runAt, String timezone) {
        return switch (type) {
            case RECURRING -> AutomationSchedule.upcoming(cron, timezone, Instant.now(), UPCOMING);
            case ONCE -> runAt == null ? List.of() : List.of(runAt);
            case API -> List.of();
        };
    }

    // --- validation ---

    private record Spec(String name, String prompt, AutomationType type, String cron, Instant runAt,
                        String timezone, String model, OverlapPolicy overlap, boolean enabled) {
    }

    /** A request with every omitted field taken from the current automation. */
    private static SaveAutomationRequest merge(Automation cur, SaveAutomationRequest r) {
        return new SaveAutomationRequest(
                r.name() != null ? r.name() : cur.name(),
                r.prompt() != null ? r.prompt() : cur.prompt(),
                r.type() != null ? r.type() : cur.type().name(),
                r.cron() != null ? r.cron() : cur.cron(),
                r.runAt() != null ? r.runAt() : localRunAt(cur),
                r.timezone() != null ? r.timezone() : cur.timezone(),
                r.model() != null ? r.model() : cur.model(),
                r.overlap() != null ? r.overlap() : cur.overlap().name(),
                r.enabled() != null ? r.enabled() : cur.enabled());
    }

    private static String localRunAt(Automation cur) {
        return cur.runAt() == null ? null
                : cur.runAt().atZone(AutomationSchedule.zone(cur.timezone())).toLocalDateTime().toString();
    }

    private Spec validate(SaveAutomationRequest r, Instant now, boolean checkRunAtFuture, boolean checkModel) {
        String prompt = r.prompt() == null ? "" : r.prompt().strip();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("Prompt is required");
        }
        String name = r.name() == null || r.name().isBlank() ? defaultName(prompt) : r.name().strip();
        AutomationType type = parse(AutomationType.class, r.type(), AutomationType.RECURRING, "type");
        String timezone = AutomationSchedule.zone(r.timezone()).getId();
        String cron = null;
        Instant runAt = null;
        if (type == AutomationType.RECURRING) {
            cron = AutomationSchedule.normalizeCron(r.cron());
        } else if (type == AutomationType.ONCE) {
            runAt = AutomationSchedule.parseLocal(r.runAt(), timezone);
            if (checkRunAtFuture && !runAt.isAfter(now)) {
                throw new IllegalArgumentException("Run time " + r.runAt() + " (" + timezone + ") is in the past");
            }
        }
        String model = r.model() == null || r.model().isBlank() ? null : r.model().strip();
        if (checkModel && model != null && providers.cliFor(model).isEmpty()) {
            throw new IllegalArgumentException("Model " + model + " has not passed the inference check");
        }
        OverlapPolicy overlap = parse(OverlapPolicy.class, r.overlap(), OverlapPolicy.SKIP, "overlap");
        boolean enabled = r.enabled() == null || r.enabled();
        return new Spec(name, prompt, type, cron, runAt, timezone, model, overlap, enabled);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String field) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private static String defaultName(String prompt) {
        String oneLine = prompt.replaceAll("\\s+", " ");
        return oneLine.length() <= 30 ? oneLine : oneLine.substring(0, 30) + "…";
    }

    // --- access, tokens, mapping ---

    private Automation require(String id) {
        return store.find(id).orElseThrow(() -> new AutomationNotFoundException(id));
    }

    /** Someone else's automation reads as "not found", so ids can't be probed. */
    private Automation requireVisible(String id, String user, boolean admin) {
        Automation a = require(id);
        if (!admin && !Objects.equals(a.owner(), user)) {
            throw new AutomationNotFoundException(id);
        }
        return a;
    }

    private Automation requireEditable(String id, String user, boolean admin) {
        Automation a = require(id);
        if (!admin && !Objects.equals(a.owner(), user)) {
            throw new AutomationForbiddenException("Only the owner or an admin can change this automation");
        }
        return a;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** SHA-256 is enough: tokens are 256 random bits, so there's nothing to brute-force from the hash. */
    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private AutomationDto toDto(Automation a, String token) {
        String lastStatus = a.lastTaskId() == null ? null
                : tasks.status(a.lastTaskId()).map(st -> st.name().toLowerCase(Locale.ROOT)).orElse(null);
        String triggerPath = a.type() == AutomationType.API
                ? ApiPaths.AUTOMATIONS + "/" + a.id() + "/trigger" : null;
        return new AutomationDto(a.id(), a.name(), a.prompt(), a.type().name().toLowerCase(Locale.ROOT), a.cron(),
                a.runAt(), a.timezone(), a.model(), a.overlap().name().toLowerCase(Locale.ROOT), a.enabled(),
                a.source(), a.owner(), a.nextRunAt(), a.lastRunAt(), a.lastTaskId(), lastStatus, triggerPath, token,
                a.createdAt(), a.updatedAt());
    }

    private AutomationRunDto toRunDto(AutomationRun r) {
        String status = r.taskId() == null ? "skipped"
                : tasks.status(r.taskId()).map(st -> st.name().toLowerCase(Locale.ROOT)).orElse("deleted");
        return new AutomationRunDto(r.id(), r.trigger(), r.taskId(), status, r.skippedReason(), r.firedAt());
    }
}
