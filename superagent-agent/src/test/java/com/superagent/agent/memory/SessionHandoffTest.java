package com.superagent.agent.memory;

import com.superagent.agent.log.AgentLogService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentKind;
import com.superagent.core.agent.AgentStatus;
import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.chat.AgentRunner;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.usage.UsageContext;
import com.superagent.core.usage.UsageRecorder;
import com.superagent.platform.provider.ProviderService;
import com.superagent.platform.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The handoff summary is one cheap, tool-less run billed to the agent's owner as {@code session_handoff}. */
class SessionHandoffTest {

    private final Agent agent = new Agent("a1", "Ada", null, null, null, null, AgentStatus.ACTIVE, "alice",
            AgentKind.STANDARD, null, Instant.now(), Instant.now());
    private final List<ChatMessage> session = List.of(
            new ChatMessage("m1", "old", MessageRole.USER, "把对账脚本改成按周跑", Instant.now()));

    private AgentMemoryService memory(AgentRunner runner, UsageRecorder usage) {
        ProviderService providers = mock(ProviderService.class);
        when(providers.cliFor(anyString())).thenReturn(Optional.of("claude"));
        SettingsService settings = mock(SettingsService.class);
        when(settings.agentDefaultModel()).thenReturn("haiku");
        AgentMemoryService m = new AgentMemoryService(null, null, null, providers, settings, List.of(runner),
                10, 2, 30, 3, "Asia/Shanghai", 10, 60000, mock(AgentLogService.class));
        m.setUsageRecorder(usage);
        return m;
    }

    private static AgentRunner runner(Runnable during) {
        AgentRunner r = mock(AgentRunner.class);
        when(r.type()).thenReturn(AgentType.CLAUDE);
        org.mockito.Mockito.doAnswer(i -> {
            during.run();
            com.superagent.core.chat.AgentEventSink sink = i.getArgument(1);
            sink.emit(UsageRecorder.event("x", 1, "{\"model\":\"haiku\"}"));
            sink.emit(new AgentEvent("x", null, 2, AgentEventType.RESULT, "## 正在做什么\n对账脚本", Instant.now()));
            return null;
        }).when(r).run(any(), any(), any());
        return r;
    }

    @Test
    void theSummaryIsBilledAsSessionHandoffToTheOwner() throws Exception {
        UsageRecorder usage = mock(UsageRecorder.class);
        List<String> why = new ArrayList<>();
        String out = TenantContext.callChecked("A", () -> memory(runner(() -> { }), usage)
                .handoff(agent, session, Duration.ofSeconds(5), why::add));
        assertThat(out).contains("对账脚本");
        ArgumentCaptor<UsageContext> ctx = ArgumentCaptor.forClass(UsageContext.class);
        verify(usage).record(ctx.capture(), anyString());
        assertThat(ctx.getValue().source()).isEqualTo("session_handoff");
        assertThat(ctx.getValue().user()).isEqualTo("alice");
        assertThat(ctx.getValue().agentId()).isEqualTo("a1");
    }

    @Test
    void aSlowSummaryTimesOutAndSaysSo() throws Exception {
        List<String> why = new ArrayList<>();
        String out = TenantContext.callChecked("A", () -> memory(runner(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }), mock(UsageRecorder.class)).handoff(agent, session, Duration.ofMillis(100), why::add));
        assertThat(out).isNull();
        assertThat(why).singleElement().asString().contains("超时");
    }
}
