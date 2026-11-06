package com.superagent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.agent.AgentInviteService;
import com.superagent.agent.AgentService;
import com.superagent.core.agent.Agent;
import com.superagent.core.agent.AgentInvite;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.user.User;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * The SuperAgent manages collaborators on any agent its user owns — the ones it hired and the ones the user made —
 * from its chat. Inviting only proposes: the card shows in the SuperAgent chat and the user confirms there.
 * Background rounds don't get these tools: they read third-party messages, which must not bring people in.
 */
@Component
class SuperAgentCollabTools implements McpToolProvider {

    /** Tool names as the CLI sees them, for the SuperAgent chat's allowlist. */
    static final List<String> TOOLS = List.of("mcp__superagent__collab_find_people",
            "mcp__superagent__collab_list_members", "mcp__superagent__collab_invite", "mcp__superagent__collab_remove");

    private final AgentService agents;
    private final AgentInviteService invites;
    private final SuperAgentService superAgent;

    SuperAgentCollabTools(AgentService agents, AgentInviteService invites, SuperAgentService superAgent) {
        this.agents = agents;
        this.invites = invites;
        this.superAgent = superAgent;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.SUPER);
    }

    @Override
    public List<ObjectNode> tools() {
        ObjectNode usernames = req("usernames", "array", "要邀请的人的用户名（先用 collab_find_people 查到）");
        usernames.putObject("items").put("type", "string");
        ObjectNode agentId = req("agent_id", "string", "Agent 的 id（用 list_team 查到；你自己不能被共享）");
        return List.of(
                tool("collab_find_people", "按姓名、用户名或手机号查找同事，用于邀请协作者。重名时会返回多位，请向用户确认是哪一位。",
                        props(req("query", "string", "姓名、用户名或手机号的一部分")), "query"),
                tool("collab_list_members", "列出用户某个 Agent 的所有者和协作者。", props(agentId.deepCopy()), "agent_id"),
                tool("collab_invite", "邀请同事成为用户某个 Agent 的协作者（他们将能看到这个 Agent 的全部对话）。只在用户明确要求时使用。"
                                + "这只会在聊天里生成一张确认卡片，用户点确认后才生效——请如实告诉用户需要他点确认，不要说已经加好了。",
                        props(agentId.deepCopy(), usernames), "agent_id", "usernames"),
                tool("collab_remove", "把某人移出用户某个 Agent 的协作者，立即生效。",
                        props(agentId.deepCopy(), req("username", "string", "要移除的人的用户名")), "agent_id", "username"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "协作");
        if (superAgent.isBackground(grant) || grant.sessionId() == null) {
            throw new McpToolException("管理协作者只能在和用户的聊天里进行");
        }
        try {
            String text = switch (name) {
                case "collab_find_people" -> {
                    List<User> found = invites.findPeople(user, require(args, "query"));
                    yield found.isEmpty() ? "没有找到匹配的同事。" : String.join("\n", found.stream().map(u ->
                            "- " + u.username() + (u.displayName() == null ? "" : " | " + u.displayName())).toList());
                }
                case "collab_list_members" -> {
                    Agent agent = owned(user, require(args, "agent_id"));
                    StringBuilder sb = new StringBuilder("「" + agent.name() + "」的成员：\n");
                    agents.get(agent.id(), user).members().forEach(m -> sb.append("- ").append(m.username()).append(" | ")
                            .append(m.displayName()).append(" | ").append(m.role()).append("\n"));
                    yield sb.toString();
                }
                case "collab_invite" -> {
                    Agent agent = owned(user, require(args, "agent_id"));
                    List<String> names = new ArrayList<>();
                    args.path("usernames").forEach(n -> names.add(n.asText("")));
                    AgentInvite invite = invites.propose(agent.id(), user, names, grant.sessionId(), grant.messageId());
                    yield "已在聊天里生成邀请确认卡片：邀请 " + String.join("、", invite.usernames()) + " 加入「" + agent.name()
                            + "」。用户在卡片上点「确认邀请」后才会生效，请这样告诉用户。";
                }
                case "collab_remove" -> {
                    Agent agent = owned(user, require(args, "agent_id"));
                    String target = require(args, "username");
                    agents.removeMember(agent.id(), user, target);
                    yield "已把 " + target + " 移出「" + agent.name() + "」的协作者。";
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }

    /** One of the user's own agents other than the SuperAgent (an agent shared with them isn't theirs to share). */
    private Agent owned(String user, String id) {
        Agent agent = agents.require(id, user);
        if (agent.isSuper()) {
            throw new McpToolException("SuperAgent 自己不能被共享");
        }
        return agent;
    }
}
