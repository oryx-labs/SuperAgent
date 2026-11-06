package com.superagent.agent.chat;

import com.superagent.core.agent.Agent;
import com.superagent.core.chat.ChatSession;
import com.superagent.runtime.mcp.McpGrants;

import java.util.List;

/**
 * A kind of agent whose chat turns run like any other but with a character, tools and MCP grant of their own
 * (the SuperAgent, which lives in a module above this one). Everything else — streaming, live view, memory,
 * session health, key failover — is the normal chat turn.
 */
public interface AgentTurnCustomizer {

    boolean handles(Agent agent);

    /** The whole system prompt (replaces the character, skill index and files hint). */
    String systemPrompt(Agent agent);

    /** MCP tool names the CLI may call, on top of the built-in tools. */
    List<String> tools(Agent agent);

    /** The grant the turn's MCP calls run under; closed by the caller. */
    McpGrants.Grant openGrant(Agent agent, String user, ChatSession session);

    /** The prompt actually sent for {@code userPrompt}: may add context the agent's CLI conversation lacks. */
    String prompt(Agent agent, ChatSession session, String userPrompt);
}
