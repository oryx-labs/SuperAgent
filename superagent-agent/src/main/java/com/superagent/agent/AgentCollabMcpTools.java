package com.superagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static com.superagent.runtime.mcp.McpTools.opt;
import static com.superagent.runtime.mcp.McpTools.props;
import static com.superagent.runtime.mcp.McpTools.req;
import static com.superagent.runtime.mcp.McpTools.require;
import static com.superagent.runtime.mcp.McpTools.requireOwner;
import static com.superagent.runtime.mcp.McpTools.tool;

/**
 * Collaborators on the agent being talked to, managed in conversation. Every call acts with the authority of the
 * person whose message is being answered; inviting only proposes, the owner confirms on the card in the chat.
 */
@Component
public class AgentCollabMcpTools implements McpToolProvider {

    /** Tool names as the CLI sees them, for the chat allowlist. */
    public static final List<String> TOOLS = List.of("mcp__superagent__collab_find_people",
            "mcp__superagent__collab_list_members", "mcp__superagent__collab_invite", "mcp__superagent__collab_remove");

    private final AgentService agents;
    private final AgentInviteService invites;

    public AgentCollabMcpTools(AgentService agents, AgentInviteService invites) {
        this.agents = agents;
        this.invites = invites;
    }

    @Override
    public Set<McpGrants.Kind> kinds() {
        return Set.of(McpGrants.Kind.CHAT);
    }

    @Override
    public List<ObjectNode> tools() {
        ObjectNode usernames = req("usernames", "array", "要邀请的人的用户名（先用 collab_find_people 查到）");
        usernames.putObject("items").put("type", "string");
        return List.of(
                tool("collab_find_people", "按姓名、用户名或手机号查找同事，用于邀请协作者。重名时会返回多位，请向用户确认是哪一位。",
                        props(req("query", "string", "姓名、用户名或手机号的一部分")), "query"),
                tool("collab_list_members", "列出当前这个 Agent 的所有者和协作者。", props(), (String[]) null),
                tool("collab_invite", "邀请同事成为当前这个 Agent 的协作者（他们将能看到全部对话）。只在用户明确要求时使用。"
                                + "这只会在对话里生成一张确认卡片，由 Agent 的所有者点确认后才生效——请如实告诉用户需要他点确认，不要说已经加好了。",
                        props(usernames), "usernames"),
                tool("collab_remove", "把某人移出当前这个 Agent 的协作者，立即生效。只有所有者能移除别人；协作者只能移除自己（退出）。",
                        props(req("username", "string", "要移除的人的用户名")), "username"));
    }

    @Override
    public McpToolResult call(McpGrants.Grant grant, String name, JsonNode args) {
        String user = requireOwner(grant, "协作");
        String agentId = grant.agentId();
        if (agentId == null) {
            throw new McpToolException("这次对话不属于某个 Agent，无法管理协作者");
        }
        try {
            String text = switch (name) {
                case "collab_find_people" -> {
                    List<User> found = invites.findPeople(user, require(args, "query"));
                    yield found.isEmpty() ? "没有找到匹配的同事。" : String.join("\n", found.stream().map(u ->
                            "- " + u.username() + (u.displayName() == null ? "" : " | " + u.displayName())).toList());
                }
                case "collab_list_members" -> {
                    StringBuilder sb = new StringBuilder();
                    agents.get(agentId, user).members().forEach(m -> sb.append("- ").append(m.username()).append(" | ")
                            .append(m.displayName()).append(" | ").append(m.role()).append("\n"));
                    yield sb.length() == 0 ? "这个 Agent 还没有协作者。" : sb.toString();
                }
                case "collab_invite" -> {
                    List<String> names = new ArrayList<>();
                    args.path("usernames").forEach(n -> names.add(n.asText("")));
                    AgentInvite invite = invites.propose(agentId, user, names, grant.sessionId(), grant.messageId());
                    yield "已在对话中生成邀请确认卡片（" + String.join("、", invite.usernames())
                            + "）。需要所有者在卡片上点「确认邀请」后才会生效，请这样告诉用户。";
                }
                case "collab_remove" -> {
                    String target = require(args, "username");
                    agents.removeMember(agentId, user, target);
                    yield target.equals(user) ? "已退出这个 Agent。" : "已把 " + target + " 移出协作者。";
                }
                default -> throw new McpToolException("Unknown tool: " + name);
            };
            return McpToolResult.ok(text);
        } catch (IllegalArgumentException | NotFoundException | ForbiddenException e) {
            throw new McpToolException(e.getMessage());
        }
    }
}
