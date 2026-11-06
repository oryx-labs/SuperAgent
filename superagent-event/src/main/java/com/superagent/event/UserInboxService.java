package com.superagent.event;

import com.superagent.api.inbox.InboxEventDto;
import com.superagent.api.inbox.InboxSummaryDto;
import com.superagent.api.inbox.InboxTodoDto;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDto;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxEventStore;
import com.superagent.core.inbox.InboxNotifyRequested;
import com.superagent.core.inbox.InboxTodo;
import com.superagent.core.inbox.InboxTodoStore;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.platform.integration.IntegrationService;
import com.superagent.task.TaskService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The signed-in user's outside messages (from the company's Feishu app) and the to-dos SuperAgent extracted
 * from them. Everything is scoped to the caller. The {@code superAgent*} methods are what SuperAgent's inbox
 * tools do on the owner's behalf.
 */
@Service
public class UserInboxService {

    private final EventSourceStore sources;
    private final IntegrationService integrations;
    private final InboxEventStore events;
    private final InboxTodoStore todos;
    private final TaskService tasks;
    private final ApplicationEventPublisher publisher;

    /** How far back stats, search and refs look. */
    private static final int RECENT = 2000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public UserInboxService(EventSourceStore sources, IntegrationService integrations, InboxEventStore events,
                            InboxTodoStore todos, TaskService tasks, ApplicationEventPublisher publisher) {
        this.sources = sources;
        this.integrations = integrations;
        this.events = events;
        this.todos = todos;
        this.tasks = tasks;
        this.publisher = publisher;
    }

    /** Whether the owner is linked to the company's Feishu app, and what arrived for them today. */
    public InboxSummaryDto summary(String owner) {
        String appId = integrations.value(IntegrationService.FEISHU, "appId").orElse(null);
        boolean linked = sources.find(owner, EventSource.FEISHU).filter(s -> s.linkedTo(appId)).isPresent();
        List<InboxEvent> today = recentOf(owner).stream().filter(e -> !e.receivedAt().isBefore(startOfToday())).toList();
        return new InboxSummaryDto(linked, today.size(), (int) today.stream().filter(InboxEvent::processed).count());
    }

    public List<InboxEventDto> events(String owner, int limit) {
        return events.recent(owner, Math.max(1, Math.min(limit, 500))).stream().map(UserInboxService::toDto).toList();
    }

    public List<InboxTodoDto> todos(String owner) {
        return todos.list(owner).stream().map(UserInboxService::toDto).toList();
    }

    public InboxTodoDto setTodoStatus(String owner, String id, String status) {
        InboxTodo t = require(owner, id);
        InboxTodo.TodoStatus s;
        try {
            s = InboxTodo.TodoStatus.valueOf(status == null ? "" : status.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Status must be open, done or dismissed");
        }
        if (s == InboxTodo.TodoStatus.TASKED) {
            throw new IllegalArgumentException("Use the to-task action to turn a to-do into a task");
        }
        InboxTodo next = t.withStatus(s, null);
        todos.save(next);
        publisher.publishEvent(changed(next));
        return toDto(next);
    }

    /** Starts a task from a to-do, run by its owner; the to-do keeps a link to it. */
    public InboxTodoDto toTask(String owner, String id, String extra) {
        InboxTodo t = require(owner, id);
        if (t.status() == InboxTodo.TodoStatus.TASKED && t.taskId() != null) {
            return toDto(t);
        }
        StringBuilder brief = new StringBuilder(t.title());
        if (t.detail() != null) {
            brief.append("\n\n背景：").append(t.detail());
        }
        if (t.requester() != null) {
            brief.append("\n提出人：").append(t.requester());
        }
        if (t.due() != null) {
            brief.append("\n截止：").append(t.due());
        }
        if (t.source() != null) {
            brief.append("\n来源：").append(t.source());
        }
        if (extra != null && !extra.isBlank()) {
            brief.append("\n\n补充要求：").append(extra.strip());
        }
        TaskDto task = tasks.create(new CreateTaskRequest(t.title(), brief.toString(), "inbox", null, null, null,
                null, null, null), owner);
        InboxTodo next = t.withStatus(InboxTodo.TodoStatus.TASKED, task.id());
        todos.save(next);
        publisher.publishEvent(changed(next));
        return toDto(next);
    }

    // --- what SuperAgent does with received messages ---

    /** Messages matching {@code query} (sender, chat or text; blank = all), newest first. */
    public List<InboxEvent> superAgentSearch(String owner, String query, int limit) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return recentOf(owner).stream()
                .filter(e -> q.isEmpty() || Stream.of(e.senderName(), e.chatName(), e.text())
                        .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(q)))
                .limit(Math.max(1, Math.min(limit, 200))).toList();
    }

    /** A to-do for the owner, pointing back at the messages it came from (which are marked as such). */
    public InboxTodoDto superAgentAddTodo(String owner, String title, String detail, String requester, String due,
                                          List<String> refs) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A to-do needs a title");
        }
        List<InboxEvent> from = resolve(owner, refs);
        String where = from.stream().map(InboxEvent::chatName).filter(n -> n != null && !n.isBlank())
                .distinct().reduce((a, b) -> a + "、" + b).orElse(null);
        Instant now = Instant.now();
        InboxTodo todo = new InboxTodo(UUID.randomUUID().toString(), owner, clip(title.strip(), 480), blank(detail),
                blank(requester), blank(due), from.isEmpty() ? null : String.join(",", from.stream().map(InboxEvent::id).toList()),
                where, InboxTodo.TodoStatus.OPEN, null, now, now);
        todos.save(todo);
        publisher.publishEvent(changed(todo));
        mark(from, "todo");
        return toDto(todo);
    }

    /** Pushes a line to the owner at their sources now (e.g. their Feishu DM). */
    public void superAgentNotify(String owner, String text, List<String> refs) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Nothing to send");
        }
        publisher.publishEvent(new InboxNotifyRequested(owner, "【SuperAgent】" + clip(text.strip(), 500)));
        mark(resolve(owner, refs), "notified");
    }

    /** Records what SuperAgent did with messages it didn't turn into a to-do or a notification. */
    public int superAgentMark(String owner, List<String> refs, String handling) {
        if (!List.of("read", "ignored", "task").contains(handling)) {
            throw new IllegalArgumentException("handling must be read, ignored or task");
        }
        List<InboxEvent> events = resolve(owner, refs);
        mark(events, handling);
        return events.size();
    }

    private List<InboxEvent> resolve(String owner, List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = refs.stream().map(String::strip).filter(r -> !r.isEmpty()).collect(Collectors.toSet());
        return recentOf(owner).stream().filter(e -> wanted.contains(e.ref()) || wanted.contains(e.id())).toList();
    }

    private void mark(List<InboxEvent> events, String handling) {
        events.forEach(e -> this.events.update(e.handledAs(handling)));
    }

    private List<InboxEvent> recentOf(String owner) {
        return events.recent(owner, RECENT);
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }

    /** The to-do page mirrors inbox to-dos; this is what it's told on every change. */
    static NoticeEvents.InboxTodoChanged changed(InboxTodo t) {
        return new NoticeEvents.InboxTodoChanged(t.id(), t.owner(), t.title(), t.detail(), t.requester(), t.due(),
                t.source(), t.status().name().toLowerCase(Locale.ROOT), t.taskId());
    }

    /** Ignoring a to-do on the to-do page dismisses it here too. */
    @EventListener
    public void onIgnored(NoticeEvents.NoticeIgnored e) {
        if ("todo".equals(e.linkType())) {
            todos.find(e.linkId()).filter(t -> t.owner().equals(e.owner()) && t.status() == InboxTodo.TodoStatus.OPEN)
                    .ifPresent(t -> todos.save(t.withStatus(InboxTodo.TodoStatus.DISMISSED, null)));
        }
    }

    private InboxTodo require(String owner, String id) {
        return todos.find(id).filter(t -> t.owner().equals(owner))
                .orElseThrow(() -> new IllegalArgumentException("No such to-do: " + id));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static InboxEventDto toDto(InboxEvent e) {
        return new InboxEventDto(e.id(), e.source(), e.chatName(), e.chatType(), e.senderName(), e.text(),
                e.mentionsMe(), e.priority().name(), e.triageReason(), e.processed(), e.handling(), e.receivedAt());
    }

    static InboxTodoDto toDto(InboxTodo t) {
        return new InboxTodoDto(t.id(), t.title(), t.detail(), t.requester(), t.due(), t.source(),
                t.status().name().toLowerCase(Locale.ROOT), t.taskId(), t.createdAt());
    }
}
