package com.superagent.platform.notice;

import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.agent.AgentStore;
import com.superagent.core.automation.AutomationStore;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxTodoStore;
import com.superagent.core.notice.Notice;
import com.superagent.core.notice.NoticeEvents;
import com.superagent.core.notice.NoticeStore;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.task.TaskStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NoticeServiceTest {

    private static final class Store implements NoticeStore {
        final Map<String, Notice> rows = new LinkedHashMap<>();
        @Override public void save(Notice n) { rows.put(n.id(), n); }
        @Override public Optional<Notice> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public Optional<Notice> findByKey(String owner, String key) {
            return rows.values().stream().filter(n -> n.owner().equals(owner) && n.key().equals(key)).findFirst();
        }
        @Override public List<Notice> list(String owner, int limit) {
            return rows.values().stream().filter(n -> n.owner().equals(owner) && n.kind() != Notice.Kind.TRACK).toList();
        }
    }

    private final Store store = new Store();
    private final List<Object> published = new ArrayList<>();
    private final NoticeService service;

    NoticeServiceTest() {
        AgentStore agents = Mockito.mock(AgentStore.class);
        Instant t = Instant.now();
        Mockito.when(agents.find("ag1")).thenReturn(Optional.of(new Agent("ag1", "写手", null, null, null, null,
                AgentStatus.ACTIVE, "ada", AgentKind.STANDARD, null, t, t)));
        service = new NoticeService(store, agents, Mockito.mock(AutomationStore.class), Mockito.mock(TaskStore.class),
                Mockito.mock(InboxTodoStore.class), Mockito.mock(EventSourceStore.class),
                Mockito.mock(SystemSettingStore.class), published::add, "Asia/Shanghai");
    }

    private void task(String status, String agentId) {
        service.onTask(new NoticeEvents.TaskStatusChanged("t1", "ada", "写周报", "web_chat", status, null, agentId));
    }

    @Test
    void oneTaskIsOneItemThatMovesWithItsStatus() {
        task("pending", "ag1");
        assertThat(service.list("ada")).isEmpty(); // running work isn't shown
        task("needs_confirmation", null);
        assertThat(service.list("ada")).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo("confirm");
            assertThat(n.actor()).isEqualTo("写手"); // remembered from creation
        });
        task("running", null); // the user answered: no longer waiting on them
        assertThat(service.list("ada")).isEmpty();
        service.onTask(new NoticeEvents.TaskStatusChanged("t1", "ada", "写周报", "web_chat", "done", null, null,
                "## 周报\n**本周**完成了登录改造。"));
        assertThat(service.list("ada")).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo("done");
            assertThat(n.body()).isEqualTo("周报 本周完成了登录改造。"); // what came out, as plain text
            assertThat(n.state()).isEqualTo("open");
        });
        assertThat(service.markAllRead("ada", EnumSet.of(Notice.Kind.DONE, Notice.Kind.NOTICE))).isEqualTo(1);
        assertThat(service.list("ada")).singleElement().satisfies(n -> assertThat(n.state()).isEqualTo("done"));
        assertThat(service.list("bob")).isEmpty();
    }

    @Test
    void plansCloseOnApprovalAndIgnoredTodosAreHandedBack() {
        service.onPlan(new NoticeEvents.PlanChanged("ada", "p1", "调研三家竞品", true));
        assertThat(service.list("ada")).singleElement().satisfies(n -> assertThat(n.kind()).isEqualTo("confirm"));
        service.onPlan(new NoticeEvents.PlanChanged("ada", "p1", null, false));
        assertThat(service.list("ada")).singleElement().satisfies(n -> assertThat(n.state()).isEqualTo("done"));

        service.onTodo(new NoticeEvents.InboxTodoChanged("td1", "ada", "回复老板", "支付超时", "王老板", "今天", "私聊",
                "open", null));
        String id = service.list("ada").stream().filter(n -> n.kind().equals("action")).findFirst().orElseThrow().id();
        service.ignore("ada", id);
        assertThat(published).singleElement().isEqualTo(new NoticeEvents.NoticeIgnored("ada", "todo", "td1"));
    }
}
