package com.superagent.web.agentlog;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.agent.AgentLogDto;
import com.superagent.web.agent.AgentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** An agent's run log, for its owner only (another user's agent reads as "not found"). */
@RestController
public class AgentLogController {

    private final AgentService agents;
    private final AgentLogService logs;

    public AgentLogController(AgentService agents, AgentLogService logs) {
        this.agents = agents;
        this.logs = logs;
    }

    @GetMapping(ApiPaths.AGENTS + "/{id}/logs")
    public ApiResponse<List<AgentLogDto>> logs(@PathVariable String id,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(defaultValue = "300") int limit,
                                               Authentication auth) {
        agents.require(id, auth.getName());
        return ApiResponse.ok(logs.list(id, category, limit));
    }
}
