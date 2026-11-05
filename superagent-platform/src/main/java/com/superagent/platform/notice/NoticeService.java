package com.superagent.platform.notice;

import com.superagent.api.notice.NoticeDto;
import com.superagent.api.notice.NoticeStatsDto;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxTodo;
import com.superagent.core.inbox.InboxTodoStore;
import com.superagent.core.notice.Notice;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.notice.NoticeStore;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.task.Task;
import com.superagent.core.task.TaskStatus;
import com.superagent.core.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The to-do home page. Modules publish {@link NoticeEvents} when something happens; this turns them
 * into items: a task waiting or failed → "needs confirmation", a task an agent finished → "done", a
 * to-do the secretary extracted → "to do", an invitation → "notice". One item per thing (its key), so a
 * task's item moves from waiting to done rather than piling up; settling the thing at its source
 * (confirming the task, approving the plan) closes the item. Items are kept until the user ignores them.
 */
@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);
    private static final int LIST_LIMIT = 500;
    private static final String BACKFILLED = "notice.backfilled";

    private final NoticeStore store;
    private final AgentStore agents;
    private final AutomationStore automations;
    private final TaskStore tasks;
    private final InboxTodoStore todos;
    private final EventSourceStore sources;
    private final SystemSettingStore settings;
    private final ApplicationEventPublisher publisher;
    private final ZoneId zone;

    public NoticeService(NoticeStore store, AgentStore agents, AutomationStore automations, TaskStore tasks,
                         InboxTodoStore todos, EventSourceStore sources, SystemSettingStore settings,
                         ApplicationEventPublisher publisher,
                         @Value("${superagent.notice.timezone:Asia/Shanghai}") String timezone) {
        this.zone = ZoneId.of(timezone);
        this.store = store;
        this.agents = agents;
        this.automations = automations;
        this.tasks = tasks;
        this.todos = todos;
        this.sources = sources;
        this.settings = settings;
        this.publisher = publisher;
    }

    // --- the page ---

    public List<NoticeDto> list(String owner) {
        return store.list(owner, LIST_LIMIT).stream().map(NoticeService::toDto).toList();
    }

    /**
     * Open items from the to-do list; finished work counted from the user's tasks themselves, so work done
     * before the to-do page existed counts too. Periods are in the configured zone; weeks start on Monday.
     */
    public NoticeStatsDto stats(String owner) {
        List<Notice> items = store.list(owner, LIST_LIMIT);
        int confirm = (int) items.stream().filter(n -> n.kind() == Notice.Kind.CONFIRM && n.state() == Notice.State.OPEN).count();
        int todo = (int) items.stream().filter(n -> n.kind() == Notice.Kind.ACTION && n.state() == Notice.State.OPEN).count();
        int unread = (int) items.stream().filter(n -> n.kind() == Notice.Kind.NOTICE && n.state() == Notice.State.OPEN).count();
        LocalDate today = LocalDate.now(zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
        List<Instant> finished = tasks.listTasks().stream()
                .filter(t -> owner.equals(t.createdBy()) && t.status() == TaskStatus.DONE)
                .map(t -> t.finishedAt() != null ? t.finishedAt() : t.updatedAt()).filter(Objects::nonNull).toList();
        return new NoticeStatsDto(confirm, todo, unread,
                finished.stream().filter(t -> !t.isBefore(dayStart)).count(),
                finished.stream().filter(t -> !t.isBefore(weekStart)).count(),
                finished.stream().filter(t -> !t.isBefore(monthStart)).count(),
                finished.size());
    }

    /** "Read" a finished-work item or heads-up; mark a decision / to-do as handled. */
    public NoticeDto markDone(String owner, String id) {
        return toDto(save(require(owner, id).withState(Notice.State.DONE)));
    }

    /** The user won't act on it; the module that owns the thing may follow (e.g. dismiss the inbox to-do). */
    public NoticeDto ignore(String owner, String id) {
        Notice n = save(require(owner, id).withState(Notice.State.IGNORED));
        publisher.publishEvent(new NoticeEvents.NoticeIgnored(owner, n.linkType(), n.linkId()));
        return toDto(n);
    }

    public NoticeDto reopen(String owner, String id) {
        return toDto(save(require(owner, id).withState(Notice.State.OPEN)));
    }

    /** Marks every open item of these kinds as read ("done" and "notice" items: nothing to decide). */
    public int markAllRead(String owner, Set<Notice.Kind> kinds) {
        int n = 0;
        for (Notice notice : store.list(owner, LIST_LIMIT)) {
            if (kinds.contains(notice.kind()) && notice.state() == Notice.State.OPEN) {
                save(notice.withState(Notice.State.DONE));
                n++;
            }
        }
        return n;
    }

    // --- events → items. A failure here is logged, never thrown: a to-do item must not break the work it reports on. ---

    @EventListener
    public void onTask(NoticeEvents.TaskStatusChanged e) {
        guard(() -> {
            String key = "task:" + e.taskId();
            Optional<Notice> existing = store.findByKey(e.owner(), key);
            switch (e.status()) {
                case "pending" -> {
                    if (existing.isEmpty()) { // remember who started it, for the "done" item later
                        upsert(e.owner(), key, Notice.Kind.TRACK, e.title(), null, origin(e), "task", e.taskId());
                    }
                }
                case "running" -> existing.filter(n -> n.kind() == Notice.Kind.CONFIRM && n.state() == Notice.State.OPEN)
                        .ifPresent(n -> save(n.with(Notice.Kind.TRACK, Notice.State.OPEN, n.title(), null, n.actor())));
                case "needs_confirmation" -> upsert(e.owner(), key, Notice.Kind.CONFIRM, e.title(),
                        "等待你确认后继续", actor(existing, e), "task", e.taskId());
                case "error" -> upsert(e.owner(), key, Notice.Kind.CONFIRM, e.title(),
                        "执行失败" + (e.error() == null ? "" : "：" + e.error()), actor(existing, e), "task", e.taskId());
                case "done" -> upsert(e.owner(), key, Notice.Kind.DONE, e.title(), summary(e.result()),
                        actor(existing, e), "task", e.taskId());
                default -> existing.ifPresent(n -> save(n.withState(Notice.State.IGNORED))); // stopped / deleted
            }
        });
    }

    @EventListener
    public void onPlan(NoticeEvents.PlanChanged e) {
        guard(() -> {
            String key = "plan:" + e.owner();
            if (e.open()) {
                upsert(e.owner(), key, Notice.Kind.CONFIRM, "SuperAgent 提交了一份计划，等你批准", e.summary(), "SuperAgent",
                        "superagent", e.planId());
            } else {
                store.findByKey(e.owner(), key).filter(n -> n.state() == Notice.State.OPEN)
                        .ifPresent(n -> save(n.withState(Notice.State.DONE)));
            }
        });
    }

    @EventListener
    public void onTodo(NoticeEvents.InboxTodoChanged e) {
        guard(() -> {
            String key = "todo:" + e.todoId();
            Notice.State state = switch (e.status()) {
                case "open" -> Notice.State.OPEN;
                case "dismissed" -> Notice.State.IGNORED;
                default -> Notice.State.DONE; // done, or handed to an agent as a task
            };
            String body = String.join("\n", Stream.of(e.detail(),
                    e.requester() == null ? null : "提出人：" + e.requester(), e.due() == null ? null : "截止：" + e.due(),
                    e.taskId() == null ? null : "已转成任务").filter(s -> s != null && !s.isBlank()).toList());
            Notice n = upsert(e.owner(), key, Notice.Kind.ACTION, e.title(), body.isEmpty() ? null : body,
                    e.source() == null ? "飞书" : "飞书 · " + e.source(), "todo", e.todoId());
            if (n.state() != state) {
                save(n.withState(state));
            }
        });
    }

    @EventListener
    public void onShared(NoticeEvents.AgentShared e) {
        guard(() -> upsert(e.username(), "shared:" + e.agentId(), Notice.Kind.NOTICE,
                e.byName() + " 邀请你协作「" + e.agentName() + "」", "可以在 Agent 列表的「协作 Agent」里找到它", e.byName(),
                "agent", e.agentId()));
    }

    /** One-time import of what was already waiting before the to-do page existed. */
    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        if (settings.all().containsKey(BACKFILLED)) {
            return;
        }
        guard(() -> {
            Set<String> owners = new LinkedHashSet<>();
            for (Task t : tasks.listTasks()) {
                owners.add(t.createdBy());
                if (t.status() == TaskStatus.NEEDS_CONFIRMATION || t.status() == TaskStatus.ERROR) {
                    onTask(new NoticeEvents.TaskStatusChanged(t.id(), t.createdBy(), t.title(),
                            t.source().name().toLowerCase(Locale.ROOT), t.status().name().toLowerCase(Locale.ROOT),
                            t.error(), null));
                }
            }
            sources.listEnabled(EventSource.FEISHU).forEach(s -> owners.add(s.owner()));
            for (String owner : owners) {
                for (InboxTodo t : todos.list(owner)) {
                    if (t.status() == InboxTodo.TodoStatus.OPEN) {
                        onTodo(new NoticeEvents.InboxTodoChanged(t.id(), owner, t.title(), t.detail(), t.requester(),
                                t.due(), t.source(), "open", t.taskId()));
                    }
                }
            }
            settings.put(BACKFILLED, "true");
        });
    }

    // --- helpers ---

    /** Who started a task: the agent that created it, the scheduled task, or the secretary. */
    private String origin(NoticeEvents.TaskStatusChanged e) {
        if (e.agentId() != null) {
            return agents.find(e.agentId()).map(Agent::name).orElse(null);
        }
        return switch (e.source() == null ? "" : e.source()) {
            // The run record is written just after the task is created, so at creation this is still null
            // and gets resolved when the task settles.
            case "automation" -> automations.findRunByTask(e.taskId()).flatMap(r -> automations.find(r.automationId()))
                    .map(a -> "定时任务 · " + a.name()).orElse(null);
            case "inbox" -> "秘书";
            default -> null;
        };
    }

    /** The start of the agent's final reply, as plain text: enough to know what came out without opening the task. */
    private static String summary(String result) {
        if (result == null || result.isBlank()) {
            return "已完成";
        }
        String plain = result.replaceAll("(?m)^#+\\s*", "").replaceAll("[*`>]", "").replaceAll("\\s+", " ").strip();
        return plain.length() <= 200 ? plain : plain.substring(0, 200) + "…";
    }

    private String actor(Optional<Notice> existing, NoticeEvents.TaskStatusChanged e) {
        String known = existing.map(Notice::actor).orElse(null);
        if (known != null) {
            return known;
        }
        String origin = origin(e);
        return origin == null && "automation".equals(e.source()) ? "定时任务" : origin;
    }

    private synchronized Notice upsert(String owner, String key, Notice.Kind kind, String title, String body, String actor,
                                       String linkType, String linkId) {
        Notice next = store.findByKey(owner, key)
                .map(n -> n.with(kind, Notice.State.OPEN, title, body, actor))
                .orElseGet(() -> new Notice(UUID.randomUUID().toString(), owner, key, kind, Notice.State.OPEN, title, body,
                        actor, linkType, linkId, Instant.now(), Instant.now()));
        return save(next);
    }

    private Notice save(Notice n) {
        store.save(n);
        return n;
    }

    private Notice require(String owner, String id) {
        return store.find(id).filter(n -> n.owner().equals(owner) && n.kind() != Notice.Kind.TRACK)
                .orElseThrow(() -> new NoticeNotFoundException(id));
    }

    private static void guard(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("to-do item not updated: {}", e.getMessage(), e);
        }
    }

    private static NoticeDto toDto(Notice n) {
        return new NoticeDto(n.id(), n.kind().name().toLowerCase(Locale.ROOT), n.state().name().toLowerCase(Locale.ROOT),
                n.title(), n.body(), n.actor(), n.linkType(), n.linkId(), n.createdAt(), n.updatedAt());
    }
}
