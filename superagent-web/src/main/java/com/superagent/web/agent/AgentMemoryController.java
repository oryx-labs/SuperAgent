package com.superagent.web.agent;

import com.superagent.agent.AgentService;
import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.agent.AgentMemoryDto;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** An agent's long-term memory: members read it, its owner edits it or has new conversation folded in now. */
@RestController
public class AgentMemoryController {

    private final AgentService agents;

    public AgentMemoryController(AgentService agents) {
        this.agents = agents;
    }

    @GetMapping(ApiPaths.AGENTS + "/{id}/memory")
    public ApiResponse<AgentMemoryDto> memory(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(agents.memory(id, auth.getName()));
    }

    @PutMapping(ApiPaths.AGENTS + "/{id}/memory")
    public ApiResponse<AgentMemoryDto> edit(@PathVariable String id, @RequestBody Map<String, String> body,
                                            Authentication auth) {
        return ApiResponse.ok(agents.editMemory(id, auth.getName(), body.get("memory")));
    }

    @PostMapping(ApiPaths.AGENTS + "/{id}/memory/distill")
    public ApiResponse<AgentMemoryDto> distill(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(agents.distillMemory(id, auth.getName()));
    }
}
