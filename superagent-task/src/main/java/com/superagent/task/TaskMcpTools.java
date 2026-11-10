package com.superagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.task.CreateTaskRequest;
import com.superagent.api.task.TaskDto;
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
import static com.superagent.runtime.mcp.McpTools.str;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/** Lets a chat agent create, follow and steer tasks. A task it creates belongs to the chatting user and inherits the agent's skills. */
@Component
public class TaskMcpTools implements McpToolProvider {

    private final TaskService tasks;

    public TaskMcpTools(TaskService tasks) {
        this.tasks = tasks;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("create_task", "把要动手做的事作为任务异步执行：读文件或资料、上网查证、产出交付物、多步操作、可能运行很久的事。只有用户确认了你列出的任务参数表后才能调用，绝不跳过确认。任务由你自己执行（带着你的人设、记忆和技能），不占住对话；完成、失败或需要用户确认时，结果会自动发回这个对话。",
                        props(req("title", "string", "任务标题"), req("brief", "string", "任务说明：目标、范围、验收标准"),
                                opt("repo", "string", "可选代码库"), opt("branch", "string", "可选分支"),
                                req("kind", "string", TaskKinds.DESCRIPTION),
                                opt("continues_from", "string", "接着哪个任务做（任务 id，用 list_tasks 查）：新任务会拿到它的结论，在它的基础上继续，而不是从头来")),
                        "title", "brief", "kind"),
                tool("list_tasks", "列出当前用户的任务及其状态。", props(), (String[]) null),
                tool("get_task", "查看某个任务的状态与进度。", props(req("id", "string", "任务 id")), "id"),
                tool("send_task_message", "给任务发送消息：回答确认、或变更/指挥其执行（任务非运行中时）。",
                        props(req("id", "string", "任务 id"), req("content", "string", "要发送的内容")), "id", "content"),
                tool("stop_task", "停止正在运行的任务。", props(req("id", "string", "任务 id")), "id"),
                tool("delete_task", "删除一个任务。", props(req("id", "string", "任务 id")), "id"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String text = switch (name) {
            case "create_task" -> {
                TaskDto t = tasks.create(new CreateTaskRequest(
                        str(args, "title"), str(args, "brief"), "web_chat",
                        strOrNull(args, "repo"), strOrNull(args, "branch"), null, null, null,
                        TaskKinds.require(args), null, strOrNull(args, "continues_from")),
                        grant.owner(), grant.agentId(), grant.sessionId(), grant.messageId());
                // The agent does it itself (its character, memory, skills); the result comes back to this chat.
                String how = "complex".equals(t.flow()) ? "它会先拆成子任务同时执行，全部完成后自动汇总。" : "";
                yield "已创建" + TaskKinds.label(t.flow()) + "任务 " + t.id() + "（" + t.title() + "），状态 " + t.status() + "。" + how
                        + "结果出来后会自动发到这个对话里。现在用一两句话告诉用户任务名和要做什么，然后结束这一轮，不要在对话里接着做。";
            }
            case "list_tasks" -> {
                List<TaskDto> list = tasks.list(grant.owner());
                if (list.isEmpty()) {
                    yield "当前没有任务。";
                }
                StringBuilder sb = new StringBuilder("共 " + list.size() + " 个任务：\n");
                for (TaskDto t : list) {
                    sb.append("- ").append(t.id()).append(" | ").append(t.title())
                            .append(" | ").append(t.status()).append(" | ").append(t.progress()).append("%\n");
                }
                yield sb.toString();
            }
            case "get_task" -> {
                TaskDto t = tasks.status(require(args, "id"), grant.owner());
                yield t.title() + " — 状态 " + t.status() + "，进度 " + t.progress() + "%"
                        + (t.error() != null ? "，错误：" + t.error() : "");
            }
            case "send_task_message" -> {
                tasks.sendMessage(require(args, "id"), grant.owner(), str(args, "content"));
                yield "已发送，任务将继续执行。";
            }
            case "stop_task" -> {
                tasks.stop(require(args, "id"), grant.owner());
                yield "已请求停止。";
            }
            case "delete_task" -> {
                tasks.delete(require(args, "id"), grant.owner());
                yield "已删除。";
            }
            default -> throw new McpToolException("Unknown tool: " + name);
        };
        return McpToolResult.ok(text);
    }
}
