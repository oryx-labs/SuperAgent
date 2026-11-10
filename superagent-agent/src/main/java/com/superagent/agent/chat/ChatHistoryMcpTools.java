package com.superagent.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.agent.AgentService;
import com.superagent.core.agent.Agent;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.ChatStore;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.task.TaskAgentStore;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * The agent's whole conversation, across its sessions, for its chats and for the tasks it set off: a new session only
 * carries the latest part over, and a task only gets its brief, so either can look further back for what someone said
 * (a long outline pasted earlier, a decision). Read as the person the run works for, who must be able to see the agent.
 */
@Component
public class ChatHistoryMcpTools implements McpToolProvider {

    public static final List<String> TOOLS = McpGrants.HISTORY_TOOLS;
    private static final int SNIPPET = 160;
    private static final int READ_CHARS = 20_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ChatStore chats;
    private final AgentService agents;
    private final TaskAgentStore taskAgents;

    public ChatHistoryMcpTools(ChatStore chats, AgentService agents, TaskAgentStore taskAgents) {
        this.chats = chats;
        this.agents = agents;
        this.taskAgents = taskAgents;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.TASK);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("chat_history_search", "在这个 Agent 和用户的全部历史对话里（跨会话）查找消息，返回消息 id、时间、谁说的和匹配处片段。"
                                + "新会话只带了最近一部分对话、任务只有任务说明，需要更早的原话（比如用户之前贴的长文、做过的决定）时用它，不要让用户重贴。",
                        props(opt("query", "string", "关键词（不区分大小写）；不填则列出最近的消息"),
                                opt("limit", "integer", "最多返回几条，默认 10，最多 30")),
                        (String[]) null),
                tool("chat_history_read", "读取历史对话里某条消息的完整原文（id 来自 chat_history_search，或上下文里「完整原文用 chat_history_read 读取消息 …」）。"
                                + "很长的消息分段读，用 offset 接着读。",
                        props(req("message_id", "string", "消息 id"),
                                opt("offset", "integer", "从第几个字开始读，默认 0")),
                        "message_id"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        Agent agent = agentOf(grant);
        List<ChatMessage> all = chats.listSessionsByAgent(agent.id()).stream()
                .filter(s -> s.status() != SessionStatus.DELETED).map(ChatSession::id)
                .flatMap(id -> chats.listMessages(id).stream())
                .filter(m -> m.content() != null && !m.content().isBlank())
                .sorted(Comparator.comparing(ChatMessage::createdAt)).toList();
        String text = switch (name) {
            case "chat_history_search" -> search(all, strOrNull(args, "query"), args.path("limit").asInt(10));
            case "chat_history_read" -> read(all, require(args, "message_id"), args.path("offset").asInt(0));
            default -> throw new McpToolException("Unknown tool: " + name);
        };
        return McpToolResult.ok(text);
    }

    /** A chat's own agent; a task's, the agent that set it off. The run's person must be able to see that agent. */
    private Agent agentOf(McpGrants.Grant grant) {
        String agentId = grant.agentId() != null ? grant.agentId()
                : grant.taskId() == null ? null : taskAgents.agentOf(grant.taskId()).orElse(null);
        if (agentId == null) {
            throw new McpToolException("这次运行不属于任何 Agent 的对话，没有可查的历史");
        }
        try {
            return agents.requireViewer(agentId, grant.owner());
        } catch (RuntimeException e) {
            throw new McpToolException("看不到这个 Agent 的对话");
        }
    }

    static String search(List<ChatMessage> all, String query, int limit) {
        int n = Math.max(1, Math.min(limit, 30));
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<ChatMessage> hits = all.stream()
                .filter(m -> q.isEmpty() || m.content().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparing(ChatMessage::createdAt).reversed()).limit(n).toList();
        if (hits.isEmpty()) {
            return q.isEmpty() ? "还没有对话。" : "没有找到包含「" + query.strip() + "」的消息。";
        }
        StringBuilder sb = new StringBuilder("找到 " + hits.size() + " 条（最新的在前）：\n");
        for (ChatMessage m : hits) {
            String body = m.content().strip();
            int at = q.isEmpty() ? 0 : Math.max(0, body.toLowerCase(Locale.ROOT).indexOf(q) - SNIPPET / 3);
            String snippet = body.substring(at, Math.min(body.length(), at + SNIPPET)).replace('\n', ' ');
            sb.append("- ").append(m.id()).append(" | ").append(STAMP.format(m.createdAt().atZone(ZONE))).append(" | ")
                    .append(who(m)).append(" | 共 ").append(body.length()).append(" 字 | ")
                    .append(at > 0 ? "…" : "").append(snippet).append(at + SNIPPET < body.length() ? "…" : "").append('\n');
        }
        return sb.toString();
    }

    static String read(List<ChatMessage> all, String messageId, int offset) {
        ChatMessage m = all.stream().filter(x -> x.id().equals(messageId)).findFirst()
                .orElseThrow(() -> new McpToolException("没有这条消息：" + messageId));
        String body = m.content();
        int from = Math.max(0, Math.min(offset, body.length()));
        int to = Math.min(body.length(), from + READ_CHARS);
        String head = STAMP.format(m.createdAt().atZone(ZONE)) + " " + who(m) + "（共 " + body.length() + " 字，第 "
                + from + "–" + to + " 字）：\n";
        return head + body.substring(from, to) + (to < body.length() ? "\n…（还有 " + (body.length() - to)
                + " 字，用 offset=" + to + " 接着读）" : "");
    }

    private static String who(ChatMessage m) {
        return m.role() == MessageRole.USER ? "用户" : m.role() == MessageRole.ASSISTANT ? "助手" : "系统";
    }
}
