package com.superagent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import com.superagent.task.automation.AutomationMcpTools;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The automation tools in the SuperAgent's chat, as in any agent's: "every morning at nine, …" becomes an automation
 * the user owns. Chat only: a background round reads third-party messages, which must not set up recurring work.
 * Its own {@code get_current_time} stays the SuperAgent's (one tool of that name per run).
 */
@Component
class SuperAgentAutomationTools implements McpToolProvider {

    private static final String CLOCK = "get_current_time";

    static final List<String> TOOLS = List.of("create_automation", "list_automations", "get_automation",
            "update_automation", "set_automation_enabled", "run_automation_now", "delete_automation")
            .stream().map(n -> "mcp__superagent__" + n).toList();

    private final AutomationMcpTools automations;

    SuperAgentAutomationTools(AutomationMcpTools automations) {
        this.automations = automations;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.SUPER);
    }

    @Override
    public List<ObjectNode> tools() {
        return automations.tools().stream().filter(t -> !CLOCK.equals(t.path("name").asText())).toList();
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        if (grant.sessionId() == null) {
            throw new McpToolException("后台处理不能创建或修改自动化。");
        }
        return automations.call(grant, name, args);
    }
}
