package com.superagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.agent.Agent;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import com.superagent.skills.SkillService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * Attaching skills to agents in conversation — only when asked (creating a skill attaches it nowhere). In an agent's
 * chat the target is that agent unless another is named; the SuperAgent's chat names one of the user's agents. Only
 * an agent's owner changes its skills; the SuperAgent itself has a fixed character and no skills.
 */
@Component
public class AgentSkillMcpTools implements McpToolProvider {

    /** Tool names as the CLI sees them. */
    public static final List<String> TOOLS = List.of("mcp__superagent__skill_attach", "mcp__superagent__skill_detach");

    private final AgentService agents;
    private final SkillService skills;

    public AgentSkillMcpTools(AgentService agents, SkillService skills) {
        this.agents = agents;
        this.skills = skills;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT, McpGrants.Kind.SUPER);
    }

    @Override
    public List<ObjectNode> tools() {
        return List.of(
                tool("skill_attach", "把一个 skill 挂到 Agent 上（之后它工作时会按这个规范来）。只在用户要求时使用；新建 skill 不会自动挂。",
                        props(req("skill_id", "string", "skill id（用 skill_list 查）"),
                                opt("agent_id", "string", "要挂到哪个 Agent；在某个 Agent 的对话里不填就是它自己，在 SuperAgent 的聊天里必填")),
                        "skill_id"),
                tool("skill_detach", "把一个 skill 从 Agent 上取下（skill 本身不删除）。只在用户要求时使用。",
                        props(req("skill_id", "string", "skill id"),
                                opt("agent_id", "string", "从哪个 Agent 取下；规则同 skill_attach")),
                        "skill_id"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "挂 skill");
        if (grant.kind() == McpGrants.Kind.SUPER && grant.sessionId() == null) {
            throw new McpToolException("skill 只能在和用户的聊天里维护");
        }
        String skillId = require(args, "skill_id");
        String agentId = args.hasNonNull("agent_id") && !args.path("agent_id").asText().isBlank()
                ? args.path("agent_id").asText().strip()
                : grant.kind() == McpGrants.Kind.CHAT ? grant.agentId() : null;
        if (agentId == null) {
            throw new McpToolException("请指定 agent_id（用 list_team 查）");
        }
        try {
            Agent agent;
            try {
                agent = agents.require(agentId, user);
            } catch (NotFoundException e) {
                throw new McpToolException("只有 Agent 的所有者能修改它的 skill，或者没有这个 Agent");
            }
            if (agent.isSuper()) {
                throw new McpToolException("SuperAgent 自己不挂 skill");
            }
            List<String> ids = new ArrayList<>(skills.agentSkillIds(agent.id()));
            String skillName = skills.get(skillId, user).name();
            boolean attach = name.equals("skill_attach");
            if (attach ? ids.contains(skillId) : !ids.contains(skillId)) {
                return McpToolResult.ok("「" + agent.name() + "」" + (attach ? "已经挂着" : "本来就没有挂") + " skill「" + skillName + "」。");
            }
            if (attach) {
                ids.add(skillId);
            } else {
                ids.remove(skillId);
            }
            skills.setAgentSkills(agent.id(), ids, user);
            return McpToolResult.ok((attach ? "已把 skill「" + skillName + "」挂到" : "已把 skill「" + skillName + "」从") + "「" + agent.name()
                    + "」" + (attach ? "上" : "上取下") + "，下一轮对话起生效。");
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
