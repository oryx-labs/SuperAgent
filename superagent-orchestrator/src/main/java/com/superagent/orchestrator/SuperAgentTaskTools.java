package com.superagent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.error.NotFoundException;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * The way work leaves the chat: once the user and the SuperAgent have settled what to do, it starts as a task and
 * the chat is free again. The user just agreed to it there, so no plan card; changing the team still takes one.
 * Chat only: a background round reads third-party messages, which must not set work off.
 */
@Component
class SuperAgentTaskTools implements McpToolProvider {

    static final List<String> TOOLS = List.of("mcp__superagent__start_task");

    private final SuperAgentService superAgent;

    SuperAgentTaskTools(SuperAgentService superAgent) {
        this.superAgent = superAgent;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.SUPER);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(tool("start_task", "把说定的活作为后台任务立刻开始，不占用聊天，也不用计划卡片。结束后你的后台会通知用户。",
                props(req("title", "string", "任务标题"),
                        req("brief", "string", "任务说明，要写全：目标、资料（路径或 Files 文件 id，执行者用 file_get 取）、交付标准"),
                        req("kind", "string", com.superagent.task.TaskKinds.DESCRIPTION),
                        opt("member_id", "string", "交给哪位现有成员（用 list_team 查），他的人设和技能会带上；没有合适的就不填，直接作为独立任务执行")),
                "title", "brief", "kind"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "任务");
        if (superAgent.isBackground(grant) || grant.sessionId() == null) {
            throw new McpToolException("开始任务只能在和用户的聊天里进行");
        }
        if (!"start_task".equals(name)) {
            throw new McpToolException("Unknown tool: " + name);
        }
        try {
            return McpToolResult.ok("任务已开始（id " + superAgent.startTask(user, strOrNull(args, "member_id"),
                    require(args, "title"), require(args, "brief"), grant.messageId(), com.superagent.task.TaskKinds.require(args))
                    + "）。聊天里会自动出现任务卡片（任务名和执行成员，可点击跳转）；用一两句话告诉用户开始了，提到任务名和交给了谁，然后结束这一轮，不要等它做完。");
        } catch (IllegalArgumentException | NotFoundException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
