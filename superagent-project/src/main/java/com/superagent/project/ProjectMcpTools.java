package com.superagent.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.project.ProjectMemory;
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
 * The project's shared context for the agents in it: reading all of it (the prompt carries a capped copy) and writing
 * to its memory. Outside a project both refuse.
 */
@Component
public class ProjectMcpTools implements McpToolProvider {

    private final ProjectService projects;

    public ProjectMcpTools(ProjectService projects) {
        this.projects = projects;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("project_context_read", "读你所在项目的完整共同上下文：项目说明、资料索引（项目目录里的文件和项目仓库）、项目记忆。"
                                + "提示词里只带了精简版，需要细节时调用；填 memory_id 只读那一条记忆的全文。",
                        props(opt("memory_id", "string", "只读这一条项目记忆")), (String[]) null),
                tool("project_memory_write", "往你所在项目的项目记忆里记一条：决策、约定、踩过的坑。项目里所有 Agent 都会看到。"
                                + "用户提醒你「记到项目里」时用它；填 memory_id 则修改已有的那一条。",
                        props(req("title", "string", "一句话标题"), opt("content", "string", "具体内容，简洁"),
                                opt("memory_id", "string", "要修改的那条记忆的 id")), "title"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "项目上下文");
        try {
            return McpToolResult.ok(switch (name) {
                case "project_context_read" -> projects.readContext(grant.agentId(), grant.taskId(), strOrNull(args, "memory_id"));
                case "project_memory_write" -> {
                    ProjectMemory m = projects.writeMemoryAsAgent(grant.agentId(), grant.taskId(), grant.sessionId(), user,
                            strOrNull(args, "memory_id"), require(args, "title"), strOrNull(args, "content"));
                    yield "已记进项目记忆「" + m.title() + "」（id: " + m.id() + "）。";
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            });
        } catch (IllegalArgumentException | ProjectArchivedException | com.superagent.core.error.NotFoundException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
