package com.superagent.event;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.inbox.InboxEventStore;
import com.superagent.core.inbox.InboxNotifyRequested;
import com.superagent.core.inbox.InboxProcessor;
import com.superagent.core.org.OrgStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InboxServiceTest {

    @BeforeEach
    void inCompany() {
        TenantContext.set("acme");
    }

    @AfterEach
    void leaveCompany() {
        TenantContext.clear();
    }

    private static final class Events implements InboxEventStore {
        final Map<String, InboxEvent> rows = new LinkedHashMap<>();
        @Override public boolean insertIfAbsent(InboxEvent e) { return rows.putIfAbsent(e.id(), e) == null; }
        @Override public void update(InboxEvent e) { rows.put(e.id(), e); }
        @Override public List<InboxEvent> pending(String owner, EventPriority p, int limit) {
            return rows.values().stream().filter(e -> e.owner().equals(owner) && e.priority() == p && !e.processed())
                    .sorted(Comparator.comparing(InboxEvent::receivedAt)).limit(limit).toList();
        }
        @Override public List<InboxEvent> recent(String owner, int limit) { return List.copyOf(rows.values()); }
        @Override public Optional<InboxEvent> findEvent(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public int deleteBefore(Instant cutoff) { return 0; }
    }

    private static final class Sources implements EventSourceStore {
        final Map<String, EventSource> rows = new HashMap<>();
        @Override public Optional<EventSource> find(String owner, String type) { return Optional.ofNullable(rows.get(owner)); }
        @Override public List<EventSource> listEnabled(String type) { return rows.values().stream().filter(EventSource::enabled).toList(); }
        @Override public List<EventSource> list() { return List.copyOf(rows.values()); }
        @Override public void save(EventSource s) { rows.put(s.owner(), s); }
    }

    /** ada reports to boss (shown as 老板 at the source); intern reports to ada. */
    private static final class Org implements OrgStore {
        @Override public Optional<String> manager(String u) { return Optional.ofNullable(managers().get(u)); }
        @Override public Map<String, String> managers() { return Map.of("ada", "boss", "intern", "ada"); }
        @Override public void setManager(String u, String m) { }
        @Override public List<String> teams(String u) { return List.of(); }
        @Override public Map<String, List<String>> memberships() { return Map.of(); }
        @Override public void setTeams(String u, List<String> t) { }
    }

    private static final class Users implements UserStore {
        @Override public List<User> listUsers() { return List.of(); }
        @Override public Optional<User> findById(String id) { return Optional.empty(); }
        @Override public Optional<User> findByUsername(String name) {
            Instant now = Instant.now();
            return Optional.of(new User(name, name, "boss".equals(name) ? "老板" : null, Set.of("USER"), true,
                    null, null, null, UserSource.LOCAL, now, now));
        }
        @Override public Optional<String> companyOfUsername(String name) { return Optional.empty(); }
        @Override public Optional<User> findByPhone(String phone) { return Optional.empty(); }
        @Override public Optional<User> findByExternalId(String id) { return Optional.empty(); }
        @Override public User save(User u) { return u; }
        @Override public void delete(String id) { }
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
        return new EventSource("ada", EventSource.FEISHU, "cli", true, "ou_ada", null,
                "09:00", "Asia/Shanghai", null, null, null, now, now);
    }

    private static InboxEvent msg(String id, String chatType, boolean mentionsMe, String sender, String text) {
        Instant now = Instant.now();
        return new InboxEvent(id, "ada", "feishu", id, "c", "群", chatType, null, "ou_" + sender, sender, text,
                mentionsMe, null, EventPriority.P2, null, false, null, now, now, now);
    }

    @Test
    void triagesLikeAPersonWhoKnowsTheOrgChart() {
        InboxService inbox = new InboxService(null, new Events(), new Sources(), new Recorder(), new Org(), new Users(), 30);
        Map<String, String> people = inbox.people("ada");
        assertThat(people).containsEntry("老板", "上级").containsEntry("boss", "上级").containsEntry("intern", "下属");
        assertThat(TriageRules.triage(msg("1", "p2p", true, "老板", "看下这个"), false, false, people).priority()).isEqualTo(EventPriority.P0);
        assertThat(TriageRules.triage(msg("2", "group", true, "同事", "线上故障了 @我"), false, false, people).priority()).isEqualTo(EventPriority.P0);
        assertThat(TriageRules.triage(msg("3", "group", false, "同事", "紧急！"), false, false, people).priority())
                .isEqualTo(EventPriority.P2); // someone else's urgency in a group isn't mine
        assertThat(TriageRules.triage(msg("4", "p2p", true, "同事", "帮忙看个 PR"), false, false, people).priority()).isEqualTo(EventPriority.P1);
        assertThat(TriageRules.triage(msg("5", "group", false, "同事", "收到"), false, false, people).priority()).isEqualTo(EventPriority.IGNORE);
        assertThat(TriageRules.triage(msg("6", "group", false, "CI", "构建失败"), false, true, people).priority()).isEqualTo(EventPriority.IGNORE);
        assertThat(TriageRules.triage(msg("7", "group", false, "我", "我来处理"), true, false, people).priority()).isEqualTo(EventPriority.IGNORE);
    }

    @Test
    void urgentRunsAtOnceHourlyAndDigestRunOnSchedule() {
        Events events = new Events();
        Sources sources = new Sources();
        Recorder processor = new Recorder();
        sources.save(source());
        InboxService inbox = new InboxService(null, events, sources, processor, new Org(), new Users(), 30);
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

        // SuperAgent asks to tell the owner something: it goes out through their source.
        inbox.onNotify(new InboxNotifyRequested("ada", "老板在等你回复"));
        assertThat(pushed).containsExactly("紧急", "老板在等你回复");
        assertThat(events.rows.values()).filteredOn(InboxEvent::processed).extracting(InboxEvent::handling)
                .containsOnly("delivered");
    }
}
