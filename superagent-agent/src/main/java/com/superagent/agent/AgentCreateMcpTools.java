package com.superagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * Any agent can make an agent in a chat, as the SuperAgent hires members: for the person it is talking to, under
 * itself (see {@link AgentService#createChild}). Chats only, never a task or a background round: an agent is a
 * lasting change the user agrees to first.
 */
@Component
public class AgentCreateMcpTools implements McpToolProvider {

    /** Tool names as the CLI sees them, for the chat allowlist. */
    public static final List<String> TOOLS = List.of("mcp__superagent__agent_create");

    private final AgentService agents;

    public AgentCreateMcpTools(AgentService agents) {
        this.agents = agents;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT);
    }

    @Override
    public List<ObjectNode> tools() {
        String characters = AgentCharacters.all().stream().map(c -> c.key() + "（" + c.nameZh() + "）")
                .collect(Collectors.joining("、"));
        ObjectNode skills = opt("skill_ids", "array", "可选，要绑定的技能 id（用 skill_list 查）");
        skills.putObject("items").put("type", "string");
        // "确认创建吗？" is what the chat page turns into a "✓ 确认创建" button (chat-stream.js QUICK_CONFIRM): keep it word for word.
        return List.of(tool("agent_create", "为用户新建一个 Agent，作为你的下级（放在你下面，和你在同一个项目里）。"
                        + "只在用户明确要一个新 Agent，或你判断某类工作值得交给一个专门的 Agent 长期负责、并且用户同意时使用。"
                        + "调用之前先在回复里列出名称、人设和性格，问用户「确认创建吗？」，用户确认后才调用；不要一上来就建。"
                        + "已有合适的 Agent 就用它，不要重复建。",
                props(req("name", "string", "名称，简短、说明它负责什么，如「周报助手」"),
                        req("persona", "string", "人设：负责什么、擅长什么、怎么做事、交付标准"),
                        opt("character", "string", "可选，性格：" + characters),
                        opt("model", "string", "可选，模型 id；不填用系统默认"),
                        skills), "name", "persona"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "新建 Agent");
        if (grant.kind() != McpGrants.Kind.CHAT || grant.agentId() == null) {
            throw new McpToolException("只能在和某个 Agent 的对话里新建 Agent");
        }
        if (!"agent_create".equals(name)) {
            throw new McpToolException("Unknown tool: " + name);
        }
        List<String> skillIds = new ArrayList<>();
        args.path("skill_ids").forEach(s -> skillIds.add(s.asText("")));
        try {
            Agent made = agents.createChild(user, grant.agentId(), require(args, "name"), require(args, "persona"),
                    strOrNull(args, "character"), strOrNull(args, "model"), skillIds);
            String parent = agents.parentOf(made.id());
            return McpToolResult.ok("已新建 Agent「" + made.name() + "」（id " + made.id() + "）"
                    + (parent != null ? "，放在你下面" : "，它是用户自己的 Agent（你不是它的所有者，所以没放在你下面）")
                    + "。用户在左侧 Agent 列表里就能看到它、直接和它对话。用一两句话告诉用户它负责什么、怎么找到它。");
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
