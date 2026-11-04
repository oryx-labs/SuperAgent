package com.superagent.event;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxEventStore;
import com.superagent.core.inbox.InboxProcessor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InboxServiceTest {

    private static final class Events implements InboxEventStore {
        final Map<String, InboxEvent> rows = new LinkedHashMap<>();
        @Override public boolean insertIfAbsent(InboxEvent e) { return rows.putIfAbsent(e.id(), e) == null; }
        @Override public void update(InboxEvent e) { rows.put(e.id(), e); }
        @Override public List<InboxEvent> pending(String owner, EventPriority p, int limit) {
            return rows.values().stream().filter(e -> e.owner().equals(owner) && e.priority() == p && !e.processed())
                    .sorted(Comparator.comparing(InboxEvent::receivedAt)).limit(limit).toList();
        }
        @Override public List<InboxEvent> recent(String owner, int limit) { return List.copyOf(rows.values()); }
        @Override public int deleteBefore(Instant cutoff) { return 0; }
    }

    private static final class Sources implements EventSourceStore {
        final Map<String, EventSource> rows = new HashMap<>();
        @Override public Optional<EventSource> find(String owner, String type) { return Optional.ofNullable(rows.get(owner)); }
        @Override public List<EventSource> listEnabled(String type) { return rows.values().stream().filter(EventSource::enabled).toList(); }
        @Override public void save(EventSource s) { rows.put(s.owner(), s); }
    }

    private static final class Recorder implements InboxProcessor {
        final List<String> calls = new ArrayList<>();
        @Override public Result process(EventSource s, EventPriority w, List<InboxEvent> batch) {
            calls.add(w + ":" + batch.size());
            return new Result(w == EventPriority.P0 ? "紧急" : null);
        }
    }

    private static EventSource source() {
        Instant now = Instant.now();
        return new EventSource("ada", EventSource.FEISHU, "cli", "v1:x", true, "ou_ada", null, "老板", null,
                "09:00", "Asia/Shanghai", null, null, null, null, null, now, now);
    }

    private static InboxEvent msg(String id, String chatType, boolean mentionsMe, String sender, String text) {
        Instant now = Instant.now();
        return new InboxEvent(id, "ada", "feishu", id, "c", "群", chatType, null, "ou_" + sender, sender, text,
                mentionsMe, null, EventPriority.P2, null, false, now, now, now);
    }

    @Test
    void triagesLikeAPerson() {
        EventSource s = source();
        assertThat(TriageRules.triage(s, msg("1", "p2p", true, "老板", "看下这个"), false, false).priority()).isEqualTo(EventPriority.P0);
        assertThat(TriageRules.triage(s, msg("2", "group", true, "同事", "线上故障了 @我"), false, false).priority()).isEqualTo(EventPriority.P0);
        assertThat(TriageRules.triage(s, msg("3", "group", false, "同事", "紧急！"), false, false).priority())
                .isEqualTo(EventPriority.P2); // someone else's urgency in a group isn't mine
        assertThat(TriageRules.triage(s, msg("4", "p2p", true, "同事", "帮忙看个 PR"), false, false).priority()).isEqualTo(EventPriority.P1);
        assertThat(TriageRules.triage(s, msg("5", "group", false, "同事", "收到"), false, false).priority()).isEqualTo(EventPriority.IGNORE);
        assertThat(TriageRules.triage(s, msg("6", "group", false, "CI", "构建失败"), false, true).priority()).isEqualTo(EventPriority.IGNORE);
        assertThat(TriageRules.triage(s, msg("7", "group", false, "我", "我来处理"), true, false).priority()).isEqualTo(EventPriority.IGNORE);
    }

    @Test
    void urgentRunsAtOnceHourlyAndDigestRunOnSchedule() {
        Events events = new Events();
        Sources sources = new Sources();
        Recorder processor = new Recorder();
        sources.save(source());
        InboxService inbox = new InboxService(events, sources, processor, 30);
        List<String> pushed = new ArrayList<>();
        inbox.registerNotifier(EventSource.FEISHU, (s, text) -> pushed.add(text));

        inbox.ingest(sources.rows.get("ada"), msg("a", "p2p", true, "老板", "今天必须上线"), false, false);
        inbox.ingest(sources.rows.get("ada"), msg("a", "p2p", true, "老板", "今天必须上线"), false, false); // redelivery
        inbox.ingest(sources.rows.get("ada"), msg("b", "p2p", true, "同事", "帮忙 review"), false, false);
        inbox.ingest(sources.rows.get("ada"), msg("c", "group", false, "同事", "周五团建"), false, false);
        assertThat(events.rows).hasSize(3);

        // 08:00 local: P0 + the first hourly window run; the 09:00 digest hasn't come yet.
        Instant eight = ZonedDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        inbox.tick(eight);
        assertThat(processor.calls).containsExactly("P0:1", "P1:1");
        assertThat(pushed).containsExactly("紧急");
        inbox.tick(eight.plusSeconds(600));
        assertThat(processor.calls).hasSize(2); // nothing pending, hour not up

        inbox.tick(eight.plusSeconds(3600 + 60)); // 09:01: digest
        assertThat(processor.calls).containsExactly("P0:1", "P1:1", "P2:1");
        assertThat(sources.rows.get("ada").lastDigestDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        inbox.tick(eight.plusSeconds(3600 + 120));
        assertThat(processor.calls).hasSize(3); // once a day
    }
}
