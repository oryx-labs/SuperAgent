package com.superagent.agent.chat;

import com.superagent.agent.log.AgentLogService;
import com.superagent.agent.memory.AgentMemoryService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.usage.UsageRecord;
import com.superagent.core.usage.UsageStore;
import com.superagent.platform.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A chat session that grows too long hands over to a fresh one; thresholds are the company's own. */
class SessionLengthTest {

    private final List<ChatSession> sessions = new ArrayList<>();
    private final List<ChatMessage> messages = new ArrayList<>();
    private final ChatStore store = mock(ChatStore.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final AgentLogService runLog = mock(AgentLogService.class);
    private String summary = "## 正在做什么\n对账脚本";
    private String summaryFailure;
    private boolean summaryThrows;
    private long contextOfLastTurn;
    private long turnSumNoPeak;

    private final AgentMemoryService memory = new AgentMemoryService(null, store, null, null, null, List.of(),
            10, 2, 30, 3, "Asia/Shanghai", 10, 60000, runLog) {
        @Override public String lastModel(String agentId) { return "opus"; }
        @Override public boolean modelChanged(String agentId, String model) { return false; }
        @Override public void recordModel(Agent agent, String model) { }
        @Override public void consolidateSoon(Agent agent) { }
        @Override public String read(Agent agent) { return "## 用户偏好\n- 回答用中文"; }
        @Override public String handoff(Agent agent, List<ChatMessage> session, Duration timeout, Consumer<String> why) {
            if (summaryThrows) {
                throw new IllegalStateException("boom");
            }
            why.accept(summaryFailure);
            return summary;
        }
    };

    private final UsageStore usage = new UsageStore() {
        @Override public void save(UsageRecord r) { }
        @Override public List<UsageRecord> forTask(String taskId) { return List.of(); }
        @Override public List<UsageRecord> between(String user, Instant from, Instant to) { return List.of(); }
        @Override public UsageRecord latestChatTurn(String sessionId) {
            // keyed by company: another company's session never reads this one's usage
            if (!"A".equals(TenantContext.require())) {
                return null;
            }
            if (turnSumNoPeak > 0) {
                // an older CLI's record: many requests summed, no per-request figure
                return new UsageRecord("u", Instant.now(), "alice", "chat", "a1", null, sessionId, "claude", "opus",
                        turnSumNoPeak, 0, 0, 0, 5, 0, 0, null, null);
            }
            return contextOfLastTurn == 0 ? null
                    : new UsageRecord("u", Instant.now(), "alice", "chat", "a1", null, sessionId, "claude", "opus",
                            10, contextOfLastTurn - 10, 0, 0, 5, 0, contextOfLastTurn, null, null);
        }
    };

    private final Agent agent = new Agent("a1", "Ada", null, null, null, null, AgentStatus.ACTIVE, "alice",
            AgentKind.STANDARD, null, Instant.now(), Instant.now());
    private final ChatService chat;

    SessionLengthTest() {
        when(store.listSessionsByAgent("a1")).thenAnswer(i -> List.copyOf(sessions));
        when(store.listMessages(anyString())).thenAnswer(i -> messages.stream().filter(m -> m.sessionId().equals(i.getArgument(0))).toList());
        when(store.createSession(any())).thenAnswer(i -> {
            sessions.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(settings.chatSessionMaxTokens()).thenAnswer(i -> "A".equals(TenantContext.require()) ? 300_000 : 1_000_000);
        when(settings.chatSessionMaxTurns()).thenAnswer(i -> "A".equals(TenantContext.require()) ? 40 : 100);
        chat = new ChatService(store, null, settings, null, null, null, null, memory, runLog, List.of(),
                mock(com.superagent.runtime.mcp.McpGrants.class), null, null, "/tmp/sa-session-length", "", "Read", false, 1000, false);
        chat.setUsageStore(usage);
        Instant t = Instant.now().minusSeconds(3600);
        sessions.add(new ChatSession("old", "Ada", AgentType.CLAUDE, "cli-1", "/tmp/ws", "a1", SessionStatus.ACTIVE, t, t,
                "opus", ChatSession.FIRST, 0));
    }

    private void turns(int n) {
        for (int i = 0; i < n; i++) {
            messages.add(new ChatMessage("m" + i, "old", MessageRole.ASSISTANT, "回复" + i, Instant.now()));
        }
    }

    @Test
    void aBigContextOrTooManyTurnsMovesToANewSessionInTheSameWorkspace() {
        contextOfLastTurn = 350_000;
        TenantContext.run("A", () -> {
            ChatSession next = chat.sessionForTurn(agent, "opus");
            assertThat(next.id()).isNotEqualTo("old");
            assertThat(next.reason()).isEqualTo(ChatSession.TOO_LONG);
            assertThat(next.workspaceDir()).isEqualTo("/tmp/ws");
        });
        ArgumentCaptor<String> said = ArgumentCaptor.forClass(String.class);
        verify(runLog).info(eq(agent), eq(AgentLogService.SESSION), said.capture(), anyString());
        assertThat(said.getValue()).contains("会话过长，已切换到新会话 #2", "单次请求的上下文有 350000", "300000");

        sessions.removeIf(s -> !s.id().equals("old"));
        contextOfLastTurn = 0;
        turns(41);
        TenantContext.run("A", () -> assertThat(chat.sessionForTurn(agent, "opus").reason()).isEqualTo(ChatSession.TOO_LONG));
    }

    /**
     * A turn that calls tools books the whole conversation once per request; the sum is far above the context it
     * last read, and must not switch the session.
     */
    @Test
    void aTurnThatReReadItsContextManyTimesIsNotTooLongByTheSum() {
        turnSumNoPeak = 3_000_000;
        turns(10);
        TenantContext.run("A", () -> assertThat(chat.sessionForTurn(agent, "opus").id()).isEqualTo("old"));
        assertThat(sessions).hasSize(1);
    }

    /** Without a per-request figure only the turn count can decide (older records, CLIs that don't report it). */
    @Test
    void aRecordWithoutASingleRequestFigureFallsBackToTheTurnCount() {
        turnSumNoPeak = 3_000_000;
        turns(41);
        TenantContext.run("A", () -> assertThat(chat.sessionForTurn(agent, "opus").reason()).isEqualTo(ChatSession.TOO_LONG));
    }

    @Test
    void underBothLimitsTheSessionContinues() {
        contextOfLastTurn = 299_000;
        turns(40);
        TenantContext.run("A", () -> assertThat(chat.sessionForTurn(agent, "opus").id()).isEqualTo("old"));
        assertThat(sessions).hasSize(1);
    }

    @Test
    void limitsAndUsageAreTheCompanysOwn() {
        contextOfLastTurn = 350_000;
        turns(41);
        // company B: its own, higher limits and no sight of A's usage
        TenantContext.run("B", () -> assertThat(chat.sessionForTurn(agent, "opus").id()).isEqualTo("old"));
    }

    @Test
    void theNewSessionsFirstTurnCarriesTheSummaryMemoryAndRecentMessages() {
        AgentMemoryService real = new AgentMemoryService(null, store, null, null, null, List.of(),
                10, 2, 30, 3, "Asia/Shanghai", 10, 60000, runLog) {
            @Override public String read(Agent a) { return "## 用户偏好\n- 回答用中文"; }
        };
        messages.add(new ChatMessage("u1", "old", MessageRole.USER, "把对账脚本改成按周跑", Instant.now()));
        String context = real.context(agent, java.util.Set.of(), null, summary);
        assertThat(context).contains("回答用中文", "交接摘要", "对账脚本", "把对账脚本改成按周跑");
    }

    @Test
    void aSummaryThatCantBeMadeStillSwitchesAndSaysWhy() {
        contextOfLastTurn = 350_000;
        summary = null;
        summaryFailure = "生成超时（90 秒）";
        TenantContext.run("A", () -> assertThat(chat.sessionForTurn(agent, "opus").reason()).isEqualTo(ChatSession.TOO_LONG));
        verify(runLog).warn(eq(agent), eq(AgentLogService.SESSION), anyString(), org.mockito.ArgumentMatchers.contains("生成超时"));

        sessions.removeIf(s -> !s.id().equals("old"));
        summaryThrows = true;
        TenantContext.run("A", () -> assertThat(chat.sessionForTurn(agent, "opus").reason()).isEqualTo(ChatSession.TOO_LONG));
    }
}
