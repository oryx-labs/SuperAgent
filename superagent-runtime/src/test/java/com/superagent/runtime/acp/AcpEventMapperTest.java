package com.superagent.runtime.acp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.chat.AgentEventType;
import com.superagent.core.usage.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Wire shapes captured from Grok Build CLI 1.0.77 over {@code grok agent stdio}. */
class AcpEventMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void mapsMessageThoughtToolAndPlanUpdates() throws Exception {
        assertThat(AcpEventMapper.mapUpdate(params("""
                {"update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}
                """)))
                .containsExactly(new AcpEventMapper.Mapped(AgentEventType.TEXT, "hi", List.of()));

        assertThat(AcpEventMapper.mapUpdate(params("""
                {"update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"hmm"}}}
                """)))
                .extracting(AcpEventMapper.Mapped::type)
                .containsExactly(AgentEventType.THINKING);

        assertThat(AcpEventMapper.mapUpdate(params("""
                {"update":{"sessionUpdate":"tool_call","title":"use_tool","kind":"other",
                "rawInput":{"tool_name":"superagent__echo","tool_input":{"text":"ping"}}}}
                """)))
                .containsExactly(new AcpEventMapper.Mapped(AgentEventType.TOOL_USE,
                        "use_tool: superagent__echo ping", List.of()));

        assertThat(AcpEventMapper.mapUpdate(params("""
                {"update":{"sessionUpdate":"tool_call_update","status":"completed","rawOutput":"ECHOED:ping"}}
                """)))
                .containsExactly(new AcpEventMapper.Mapped(AgentEventType.TOOL_RESULT, "ECHOED:ping", List.of()));
    }

    @Test
    void readsGrokCachedReadTokensFromTurnCompleted() throws Exception {
        // Grok nests usage under update and spells the cache-read field cachedReadTokens.
        List<TokenUsage> tokens = AcpEventMapper.mapUsage(params("""
                {"update":{"sessionUpdate":"turn_completed","usage":{
                  "inputTokens":20,"outputTokens":8,"totalTokens":30,
                  "cachedReadTokens":2,"cacheCreationTokens":0,"reasoningTokens":1}}}
                """), "mock-model");

        assertThat(tokens).containsExactly(new TokenUsage("mock-model", 20, 2, 0, 0, 8, 1, null));
        assertThat(AcpEventMapper.isUsageUpdate(params("""
                {"update":{"sessionUpdate":"turn_completed"}}
                """))).isTrue();
        // Mid-turn partials must not be billed a second time.
        assertThat(AcpEventMapper.isUsageUpdate(params("""
                {"update":{"sessionUpdate":"response_completed"}}
                """))).isFalse();
    }

    @Test
    void picksAllowOnceFromPermissionOptions() throws Exception {
        AcpEventMapper.PermissionRequest req = AcpEventMapper.mapPermission(JSON.readTree("""
                {"toolCallId":"tc1","title":"run_terminal_command","options":[
                  {"optionId":"allow_once","name":"Allow once","kind":"allow_once"},
                  {"optionId":"reject_once","name":"Reject","kind":"reject_once"}]}
                """));
        assertThat(AcpEventMapper.optionFor(req, true)).isEqualTo("allow_once");
        assertThat(AcpEventMapper.optionFor(req, false)).isEqualTo("reject_once");
    }

    private static ObjectNode params(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }
}
