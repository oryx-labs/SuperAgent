package com.superagent.web.agent;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.agent.AgentDto;
import com.superagent.api.agent.CreateAgentRequest;
import com.superagent.api.agent.UpdateAgentRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Agent (persona) registry endpoints: CRUD plus soft delete, using the {@link ApiResponse} envelope. */
@RestController
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping(ApiPaths.AGENTS)
    public ApiResponse<List<AgentDto>> list() {
        return ApiResponse.ok(agentService.list());
    }

    @PostMapping(ApiPaths.AGENTS)
    public ApiResponse<AgentDto> create(@RequestBody(required = false) CreateAgentRequest request) {
        return ApiResponse.ok(agentService.create(request));
    }

    @GetMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<AgentDto> get(@PathVariable String id) {
        return ApiResponse.ok(agentService.get(id));
    }

    @PutMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<AgentDto> update(@PathVariable String id, @RequestBody UpdateAgentRequest request) {
        return ApiResponse.ok(agentService.update(id, request));
    }

    @DeleteMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        agentService.delete(id);
        return ApiResponse.empty();
    }
}
