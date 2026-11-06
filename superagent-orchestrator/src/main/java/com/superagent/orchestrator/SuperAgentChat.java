package com.superagent.orchestrator;

import com.superagent.agent.chat.AgentTurnCustomizer;
import com.superagent.core.agent.Agent;
import com.superagent.core.chat.ChatSession;
import com.superagent.runtime.mcp.McpGrants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The SuperAgent's chat: a normal live chat turn with the SuperAgent's character and team tools. Each turn is told
 * what the background posted into the chat since the last answer, and where the plan and team stand.
 */
@Component
class SuperAgentChat implements AgentTurnCustomizer {

    private final SuperAgentService service;
    private final McpGrants grants;
    private final int teamLimit;

    SuperAgentChat(SuperAgentService service, McpGrants grants,
                   @Value("${superagent.superagent.team-limit:6}") int teamLimit) {
        this.service = service;
        this.grants = grants;
        this.teamLimit = Math.max(1, teamLimit);
    }

    @Override
    public boolean handles(Agent agent) {
        return agent.isSuper();
    }

    @Override
    public String systemPrompt(Agent agent) {
        return SuperAgentPrompts.chat(teamLimit);
    }

    @Override
    public List<String> tools(Agent agent) {
        return SuperAgentService.CHAT_TOOLS;
    }

    @Override
    public McpGrants.Grant openGrant(Agent agent, String user, ChatSession session) {
        // The SuperAgent is never shared: its turns always act for its owner.
        return grants.available() ? grants.openSuper(agent.owner(), agent.id(), session.workspaceDir()) : null;
    }

    @Override
    public String prompt(Agent agent, ChatSession session, String userPrompt) {
        StringBuilder sb = new StringBuilder();
        List<String> posts = service.backgroundPostsSinceLastReply(session.id());
        if (!posts.isEmpty()) {
            sb.append("## 后台刚才告诉用户的话（你的后台发的，按时间顺序）\n");
            posts.forEach(p -> sb.append("- ").append(p.replace("\n", "\n  ")).append("\n"));
            sb.append("\n");
        }
        sb.append(service.statusLines(agent.owner())).append("\n## 用户说\n").append(userPrompt);
        return sb.toString();
    }
}
