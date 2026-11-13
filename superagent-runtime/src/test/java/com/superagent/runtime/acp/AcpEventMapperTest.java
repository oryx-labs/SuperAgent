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
                {"toolCall":{"toolCallId":"tc1","title":"Run a shell command","name":"run_terminal_command","kind":"execute"},
                 "options":[
                  {"optionId":"allow_once","name":"Allow once","kind":"allow_once"},
                  {"optionId":"reject_once","name":"Reject","kind":"reject_once"}]}
                """));
        assertThat(req.toolCallId()).isEqualTo("tc1");
        assertThat(req.name()).isEqualTo("run_terminal_command");
        assertThat(AcpEventMapper.permissionTool(req)).isEqualTo("Bash");
        assertThat(AcpEventMapper.optionFor(req, true)).isEqualTo("allow_once");
        assertThat(AcpEventMapper.optionFor(req, false)).isEqualTo("reject_once");
    }

    @Test
    void mapsGrokToolsOntoClaudeAllowlistVocabulary() {
        assertThat(AcpEventMapper.permissionTool("read_file", "Read README", "read")).isEqualTo("Read");
        assertThat(AcpEventMapper.permissionTool("search_replace", "Edit file", "edit")).isEqualTo("Edit");
        assertThat(AcpEventMapper.permissionTool("write_file", "Create file", null)).isEqualTo("Write");
        assertThat(AcpEventMapper.permissionTool("use_tool", "use_tool superagent__echo", "other"))
                .isEqualTo("MCPTool");
        assertThat(AcpEventMapper.permissionTool("web_search", "Search the web", "fetch"))
                .isEqualTo("WebSearch");
    }

    @Test
    void mcpToolsDoNotInheritBuiltinPermissionsFromNameSubstrings() throws Exception {
        AcpEventMapper.PermissionRequest corpRead = AcpEventMapper.mapPermission(JSON.readTree("""
                {"toolCall":{"toolCallId":"1","name":"corp__read_file","title":"Read a CRM record","kind":"read"},
                 "options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"}]}
                """));
        assertThat(AcpEventMapper.permissionTool(corpRead)).isEqualTo("MCPTool");
        assertThat(corpRead.mcpTool()).isEqualTo("mcp__corp__read_file");
        assertThat(AcpEventMapper.allowlistMatches(List.of("Read"), corpRead)).isFalse();
        assertThat(AcpEventMapper.allowlistMatches(List.of("mcp__corp"), corpRead)).isTrue();
    }

    @Test
    void serverAllowlistUsesExactServerBoundary() {
        assertThat(AcpEventMapper.mcpRuleMatches("mcp__crm", "mcp__crm__list", "crm__list")).isTrue();
        assertThat(AcpEventMapper.mcpRuleMatches("mcp__crm", "mcp__crm_admin__delete_all", "crm_admin__delete_all"))
                .isFalse();
        assertThat(AcpEventMapper.mcpRuleMatches("mcp__superagent__file_write",
                "mcp__superagent__file_write", "superagent__file_write")).isTrue();
        assertThat(AcpEventMapper.mcpRuleMatches("mcp__superagent__file_write",
                "mcp__superagent__file_read", "superagent__file_read")).isFalse();
    }

    @Test
    void useToolWrapperKeepsRealMcpIdentityForAllowlist() throws Exception {
        AcpEventMapper.PermissionRequest req = AcpEventMapper.mapPermission(JSON.readTree("""
                {"toolCall":{
                   "toolCallId":"tc9",
                   "name":"use_tool",
                   "title":"use_tool superagent__file_write",
                   "kind":"other",
                   "rawInput":{"tool_name":"superagent__file_write","tool_input":{"path":"a.md"}}
                 },
                 "options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"}]}
                """));
        assertThat(req.label()).isEqualTo("superagent__file_write");
        assertThat(req.mcpTool()).isEqualTo("mcp__superagent__file_write");
        assertThat(AcpEventMapper.permissionTool(req)).isEqualTo("MCPTool");
        assertThat(AcpEventMapper.allowlistMatches(List.of("mcp__superagent__file_write"), req)).isTrue();
        assertThat(AcpEventMapper.allowlistMatches(List.of("mcp__superagent"), req)).isTrue();
        assertThat(AcpEventMapper.allowlistMatches(List.of("Write"), req)).isFalse();
    }

    private static ObjectNode params(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }
}
