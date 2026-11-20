package com.superagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.agent.Agent;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.task.TaskAgentStore;
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
 * An agent writes its own long-term memory, one category at a time, without asking first: in a chat it's that chat's
 * agent, in a task the agent that set the task off. Its owner sees and edits the result on the agent's page.
 */
@Component
public class AgentMemoryMcpTools implements McpToolProvider {

    private final AgentService agents;
    private final TaskAgentStore taskAgents;

    public AgentMemoryMcpTools(AgentService agents, TaskAgentStore taskAgents) {
        this.agents = agents;
        this.taskAgents = taskAgents;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(tool("agent_memory_write", "直接写你自己的长期记忆（不需要先问用户），以后每次新会话都会带上。"
                        + "记忆按类别分段，常用类别：项目理解、用户偏好、工作约定、踩过的坑，也可以用新的类别。"
                        + "mode=append 往这个类别末尾追加（默认），mode=replace 用 content 改写整个类别（content 为空则删掉这个类别）。"
                        + "每条一行、写结论；写完在回复里用一句话说明记了什么。",
                props(req("category", "string", "类别，如「项目理解」「用户偏好」「工作约定」「踩过的坑」"),
                        opt("content", "string", "要记下的内容，Markdown，每条一行"),
                        opt("mode", "string", "append（追加，默认）或 replace（改写这个类别）")), "category"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "写记忆");
        if (!"agent_memory_write".equals(name)) {
            throw new McpToolException("Unknown tool: " + name);
        }
        String mode = strOrNull(args, "mode");
        boolean replace = "replace".equalsIgnoreCase(mode == null ? "" : mode.strip());
        if (mode != null && !replace && !"append".equalsIgnoreCase(mode.strip())) {
            throw new McpToolException("mode 只能是 append 或 replace");
        }
        String agentId = grant.agentId() != null ? grant.agentId()
                : grant.taskId() == null ? null : taskAgents.agentOf(grant.taskId()).orElse(null);
        if (agentId == null) {
            throw new McpToolException("这次运行不属于任何 Agent，没有记忆可写");
        }
        try {
            Agent agent = agents.requireViewer(agentId, user);
            String cat = agents.writeOwnMemory(agent, user, require(args, "category"), strOrNull(args, "content"), replace,
                    grant.sessionId(), grant.taskId());
            return McpToolResult.ok((replace ? "已改写记忆里的「" : "已记进记忆的「") + cat + "」。");
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
