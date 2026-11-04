package com.superagent.web.inbox;

import com.superagent.api.inbox.InboxEventDto;
import com.superagent.api.inbox.InboxSourceDto;
import com.superagent.api.inbox.InboxTodoDto;
import com.superagent.api.inbox.SaveInboxSourceRequest;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDto;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceChanged;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxEventStore;
import com.superagent.core.inbox.InboxTodo;
import com.superagent.core.inbox.InboxTodoStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.web.task.TaskService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * The signed-in user's view of their inbox: Feishu bot settings, received messages and extracted
 * to-dos. Everything is scoped to the caller; saving settings tells the connector to reconnect.
 */
@Service
public class UserInboxService {

    private final EventSourceStore sources;
    private final InboxEventStore events;
    private final InboxTodoStore todos;
    private final SecretCipher cipher;
    private final TaskService tasks;
    private final ApplicationEventPublisher publisher;
    private final SecureRandom random = new SecureRandom();

    public UserInboxService(EventSourceStore sources, InboxEventStore events, InboxTodoStore todos, SecretCipher cipher,
                            TaskService tasks, ApplicationEventPublisher publisher) {
        this.sources = sources;
        this.events = events;
        this.todos = todos;
        this.cipher = cipher;
        this.tasks = tasks;
        this.publisher = publisher;
    }

    public InboxSourceDto source(String owner) {
        return sources.find(owner, EventSource.FEISHU).map(UserInboxService::toDto)
                .orElse(new InboxSourceDto(EventSource.FEISHU, false, null, false, false, false, null, null,
                        String.join("\n", EventSource.DEFAULT_URGENT_KEYWORDS), "09:00", "Asia/Shanghai",
                        null, null, null, null));
    }

    public InboxSourceDto save(String owner, SaveInboxSourceRequest req) {
        EventSource cur = sources.find(owner, EventSource.FEISHU).orElse(null);
        String appId = blank(req.appId());
        if (appId == null) {
            throw new IllegalArgumentException("App ID is required");
        }
        String secret = blank(req.appSecret()) != null ? cipher.encrypt(req.appSecret().strip())
                : cur != null ? cur.secretCipher() : null;
        if (secret == null) {
            throw new IllegalArgumentException("App Secret is required");
        }
        String digest = blank(req.digestTime()) == null ? "09:00" : req.digestTime().strip();
        try {
            LocalTime.parse(digest);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Digest time must be HH:mm");
        }
        String tz = blank(req.timezone()) == null ? "Asia/Shanghai" : req.timezone().strip();
        try {
            ZoneId.of(tz);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + tz);
        }
        boolean appChanged = cur == null || !appId.equals(cur.appId());
        Instant now = Instant.now();
        EventSource next = new EventSource(owner, EventSource.FEISHU, appId, secret,
                req.enabled() == null || req.enabled(),
                appChanged ? null : cur.ownerExternalId(),                   // a different bot must be re-bound
                appChanged || cur.bindCode() == null && cur.ownerExternalId() == null ? newBindCode() : cur.bindCode(),
                limit(req.vipNames(), 4000), limit(req.urgentKeywords(), 4000), digest, tz,
                cur == null ? null : cur.secretaryAgentId(),
                // A new setup starts its first digest tomorrow rather than firing one right away.
                cur == null ? LocalDate.now(ZoneId.of(tz)) : cur.lastDigestDate(),
                cur == null ? now : cur.lastHourlyAt(),
                "connecting", null, cur == null ? now : cur.createdAt(), now);
        sources.save(next);
        publisher.publishEvent(new EventSourceChanged(owner, EventSource.FEISHU));
        return toDto(next);
    }

    /** A fresh 6-digit code to send the bot ("绑定 123456"); also re-binds after the user switched accounts. */
    public InboxSourceDto newBindCode(String owner) {
        EventSource cur = sources.find(owner, EventSource.FEISHU)
                .orElseThrow(() -> new IllegalArgumentException("Set up the Feishu bot first"));
        EventSource next = cur.withBindCode(newBindCode());
        sources.save(next);
        return toDto(next);
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
        return toDto(next);
    }

    /** Starts a task from a to-do, run by the secretary's owner; the to-do keeps a link to it. */
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
        return toDto(next);
    }

    private InboxTodo require(String owner, String id) {
        return todos.find(id).filter(t -> t.owner().equals(owner))
                .orElseThrow(() -> new IllegalArgumentException("No such to-do: " + id));
    }

    /** 8 characters from an unambiguous alphabet (no 0/O, 1/I/L): ~1e12 codes, and the bot burns a code after 5 misses. */
    private String newBindCode() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String limit(String s, int max) {
        String v = blank(s);
        return v == null ? null : v.length() <= max ? v.strip() : v.substring(0, max);
    }

    static InboxSourceDto toDto(EventSource s) {
        return new InboxSourceDto(s.type(), true, s.appId(), s.secretCipher() != null, s.enabled(),
                s.ownerExternalId() != null, s.ownerExternalId() == null ? s.bindCode() : null, s.vipNames(),
                s.urgentKeywords(), s.digestTime(), s.timezone(), s.secretaryAgentId(), s.status(), s.lastError(),
                s.updatedAt());
    }

    static InboxEventDto toDto(InboxEvent e) {
        return new InboxEventDto(e.id(), e.source(), e.chatName(), e.chatType(), e.senderName(), e.text(),
                e.mentionsMe(), e.priority().name(), e.triageReason(), e.processed(), e.receivedAt());
    }

    static InboxTodoDto toDto(InboxTodo t) {
        return new InboxTodoDto(t.id(), t.title(), t.detail(), t.requester(), t.due(), t.source(),
                t.status().name().toLowerCase(Locale.ROOT), t.taskId(), t.createdAt());
    }
}
