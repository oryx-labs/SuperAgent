package com.superagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.strOrNull;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * Lets an agent change its own settings (or a child agent's) in a chat, once the user agrees. Only the fields the
 * caller passes are touched; the rest keep their value (see {@link AgentService#mergeUpdate}). Chats only, like
 * {@link AgentCreateMcpTools}: changing an agent is a lasting edit the user confirms first.
 */
@Component
public class AgentUpdateMcpTools implements McpToolProvider {

    /** Tool names as the CLI sees them, for the chat allowlist. */
    public static final List<String> TOOLS = List.of("mcp__superagent__agent_update");

    private final AgentService agents;

    public AgentUpdateMcpTools(AgentService agents) {
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
        ObjectNode skills = opt("skill_ids", "array", "可选，要绑定的技能 id（用 skill_list 查）；传空数组清空，不传则保持原样");
        skills.putObject("items").put("type", "string");
        // "确认修改吗？" is what the chat page turns into a "✓ 确认修改" button (chat-stream.js QUICK_CONFIRM): keep it word for word.
        return List.of(tool("agent_update", "修改 Agent 的设定，只改你传的字段，其余保持不变。"
                        + "不传 agent_id 就是当前对话的 Agent；也可以改你拥有的下级 Agent。"
                        + "调用之前先在回复里列出改前和改后的内容，问用户「确认修改吗？」，用户确认后才调用。"
                        + "SuperAgent 的名称和性格不能改。",
                props(opt("agent_id", "string", "可选，要改的 Agent id；不传=当前对话的 Agent"),
                        opt("name", "string", "可选，名称"),
                        opt("persona", "string", "可选，人设：负责什么、擅长什么、怎么做事、交付标准"),
                        opt("character", "string", "可选，性格：" + characters),
                        opt("model", "string", "可选，模型 id；不填用系统默认"),
                        skills)));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "修改 Agent");
        if (grant.kind() != McpGrants.Kind.CHAT || grant.agentId() == null) {
            throw new McpToolException("只能在和某个 Agent 的对话里修改 Agent");
        }
        if (!"agent_update".equals(name)) {
            throw new McpToolException("Unknown tool: " + name);
        }
        String id = (args.has("agent_id") && !args.get("agent_id").isNull())
                ? args.get("agent_id").asText("") : grant.agentId();
        if (id.isBlank()) {
            throw new McpToolException("没有要修改的 Agent");
        }
        String newName = strOrNull(args, "name");
        String persona = strOrNull(args, "persona");
        String character = strOrNull(args, "character");
        String model = strOrNull(args, "model");
        boolean skillsSent = args.has("skill_ids") && !args.get("skill_ids").isNull();
        List<String> skillIds;
        if (skillsSent) {
            skillIds = new ArrayList<>();
            args.path("skill_ids").forEach(s -> skillIds.add(s.asText("")));
        } else {
            skillIds = null;
        }
        if (newName == null && persona == null && character == null && model == null && !skillsSent) {
            throw new McpToolException("没有要修改的字段；至少传一个要改的字段");
        }
        try {
            String summary = agents.mergeUpdate(user, id, newName, persona, character, model, skillIds);
            return McpToolResult.ok(summary);
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
