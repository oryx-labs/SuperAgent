package com.superagent.web.agent;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.agent.AgentDto;
import com.superagent.api.agent.CreateAgentRequest;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.ChatSegmentDto;
import com.superagent.core.agent.AgentCharacters;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Agent (persona) registry endpoints for the signed-in user's own agents: CRUD plus soft delete. */
@RestController
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping(ApiPaths.AGENTS)
    public ApiResponse<List<AgentDto>> list(Authentication auth) {
        return ApiResponse.ok(agentService.list(auth.getName()));
    }

    @PostMapping(ApiPaths.AGENTS)
    public ApiResponse<AgentDto> create(@RequestBody(required = false) CreateAgentRequest request,
                                       Authentication auth) {
        return ApiResponse.ok(agentService.create(request, auth.getName()));
    }

    @GetMapping(ApiPaths.AGENTS + "/avatars")
    public ApiResponse<List<String>> avatars() {
        return ApiResponse.ok(AgentService.AVATARS);
    }

    @GetMapping(ApiPaths.AGENTS + "/characters")
    public ApiResponse<List<AgentCharacters.Character>> characters() {
        return ApiResponse.ok(AgentCharacters.all());
    }

    @GetMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<AgentDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(agentService.get(id, auth.getName()));
    }

    /** The agent's whole conversation across its sessions (a model change starts a new one). */
    @GetMapping(ApiPaths.AGENTS + "/{id}/history")
    public ApiResponse<List<ChatSegmentDto>> history(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(agentService.history(id, auth.getName()));
    }

    @PutMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<AgentDto> update(@PathVariable String id, @RequestBody UpdateAgentRequest request,
                                       Authentication auth) {
        return ApiResponse.ok(agentService.update(id, request, auth.getName()));
    }

    @DeleteMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        agentService.delete(id, auth.getName());
        return ApiResponse.empty();
    }
}
