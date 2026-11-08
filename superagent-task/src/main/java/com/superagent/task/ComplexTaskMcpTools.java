package com.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.task.TaskDto;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.str;
import static com.superagent.runtime.mcp.McpTools.tool;

/** What a complex task uses to hand out its work: subtasks that run side by side, and their results. */
@Component
public class ComplexTaskMcpTools implements McpToolProvider {

    /** The allowlist entries a complex task's run gets (the server is "superagent"). */
    static final List<String> ALLOWED = List.of("mcp__superagent__create_subtask", "mcp__superagent__list_subtasks",
            "mcp__superagent__subtask_output");

    private final TaskService tasks;

    public ComplexTaskMcpTools(TaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("create_subtask", "（仅复杂任务）派出一个子任务：它和其他子任务同时执行，结果交回给你汇总。说明要写全，子任务看不到你的上下文。",
                        props(req("title", "string", "子任务标题"),
                                req("brief", "string", "子任务说明：目标、范围、资料（路径 / 文件 id / 链接原样写上）、要交回什么")),
                        "title", "brief"),
                tool("list_subtasks", "（仅复杂任务）列出你派出的子任务和它们的状态。", props(), (String[]) null),
                tool("subtask_output", "（仅复杂任务）读某个子任务的完整结果。", props(req("id", "string", "子任务 id")), "id"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String parent = grant.taskId();
        if (parent == null) {
            throw new McpToolException("只有任务里能用");
        }
        String text = switch (name) {
            case "create_subtask" -> {
                try {
                    TaskDto t = tasks.createSubtask(parent, grant.owner(), str(args, "title"), str(args, "brief"));
                    yield "已派出子任务 " + t.id() + "（" + t.title() + "），还能再派 " + tasks.subtaskRoom(parent) + " 个。";
                } catch (IllegalArgumentException e) {
                    throw new McpToolException(e.getMessage());
                }
            }
            case "list_subtasks" -> {
                List<com.superagent.core.task.Task> kids = tasks.children(parent);
                if (kids.isEmpty()) {
                    yield "还没有子任务。";
                }
                StringBuilder sb = new StringBuilder();
                kids.forEach(k -> sb.append("- ").append(k.id()).append(" | ").append(k.title()).append(" | ")
                        .append(k.status().name().toLowerCase()).append('\n'));
                yield sb.toString();
            }
            case "subtask_output" -> tasks.subtaskOutput(parent, require(args, "id"), grant.owner());
            default -> throw new McpToolException("Unknown tool: " + name);
        };
        return McpToolResult.ok(text);
    }
}
