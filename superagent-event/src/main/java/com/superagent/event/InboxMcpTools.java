package com.superagent.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.api.inbox.InboxEventDto;
import com.superagent.api.inbox.InboxTodoDto;
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

/** The chatting user's inbox: received messages and the to-dos extracted from them. */
@Component
public class InboxMcpTools implements McpToolProvider {

    private final UserInboxService inbox;

    public InboxMcpTools(UserInboxService inbox) {
        this.inbox = inbox;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("inbox_recent", "查看最近收到的消息（飞书等渠道），含优先级和分拣理由。",
                        props(opt("limit", "integer", "条数，默认 50")), (String[]) null),
                tool("todo_list", "列出从收到的消息里提炼的待办及其状态（open/tasked/done/dismissed）。",
                        props(), (String[]) null),
                tool("todo_update", "把一条待办标记为完成（done）、忽略（dismissed）或重新打开（open）。",
                        props(req("id", "string", "待办 id"), req("status", "string", "done / dismissed / open")), "id", "status"),
                tool("todo_to_task", "把一条待办转成任务并开始执行（仅在主人要求时使用）。",
                        props(req("id", "string", "待办 id"), opt("note", "string", "给任务的补充要求")), "id"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String owner = requireOwner(grant, "收件箱");
        try {
            String text = switch (name) {
                case "inbox_recent" -> {
                    List<InboxEventDto> list = inbox.events(owner, args.path("limit").asInt(50));
                    yield list.isEmpty() ? "还没有收到任何消息。" : String.join("\n", list.stream().map(e ->
                            "- [" + e.priority() + (e.processed() ? "" : " 待处理") + "] " + e.receivedAt() + " "
                                    + e.chatName() + " / " + e.senderName() + "：" + e.text()).toList());
                }
                case "todo_list" -> {
                    List<InboxTodoDto> list = inbox.todos(owner);
                    yield list.isEmpty() ? "没有待办。" : String.join("\n", list.stream().map(t ->
                            "- " + t.id() + " | " + t.status() + " | " + t.title()
                                    + (t.requester() == null ? "" : " | 提出人 " + t.requester())
                                    + (t.due() == null ? "" : " | 截止 " + t.due())
                                    + (t.taskId() == null ? "" : " | 任务 " + t.taskId())).toList());
                }
                case "todo_update" -> {
                    InboxTodoDto t = inbox.setTodoStatus(owner, require(args, "id"), require(args, "status"));
                    yield "已将「" + t.title() + "」标记为 " + t.status() + "。";
                }
                case "todo_to_task" -> {
                    InboxTodoDto t = inbox.toTask(owner, require(args, "id"), strOrNull(args, "note"));
                    yield "已把「" + t.title() + "」转成任务 " + t.taskId() + "，正在执行。";
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
