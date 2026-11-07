package com.superagent.web.agent;

import com.superagent.agent.AgentService;
import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.agent.AgentDto;
import com.superagent.api.agent.CreateAgentRequest;
import com.superagent.api.agent.InviteMembersRequest;
import com.superagent.api.agent.UpdateAgentRequest;
import com.superagent.api.chat.ChatSegmentDto;
import com.superagent.api.task.TaskDto;
import com.superagent.core.agent.AgentCharacters;
import com.superagent.task.TaskService;
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
    private final TaskService taskService;

    public AgentController(AgentService agentService, TaskService taskService) {
        this.agentService = agentService;
        this.taskService = taskService;
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

    /** Where each reply's time went (prep, CLI start, waiting for the model, tools, output), shown under it. */
    @GetMapping(ApiPaths.AGENTS + "/{id}/timings")
    public ApiResponse<List<com.superagent.api.timing.TurnTimingDto>> timings(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(agentService.timings(id, auth.getName()));
    }

    /** The tasks this agent set off, newest first — shown in its side panel. */
    @GetMapping(ApiPaths.AGENTS + "/{id}/tasks")
    public ApiResponse<List<TaskDto>> tasks(@PathVariable String id, Authentication auth) {
        agentService.get(id, auth.getName()); // access check: owner or collaborator, else throws
        return ApiResponse.ok(taskService.listForAgent(id));
    }

    @PutMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<AgentDto> update(@PathVariable String id, @RequestBody UpdateAgentRequest request,
                                       Authentication auth) {
        return ApiResponse.ok(agentService.update(id, request, auth.getName()));
    }

    /** Owner only: share the agent with colleagues. */
    @PostMapping(ApiPaths.AGENTS + "/{id}/members")
    public ApiResponse<AgentDto> invite(@PathVariable String id, @RequestBody InviteMembersRequest request,
                                        Authentication auth) {
        return ApiResponse.ok(agentService.invite(id, auth.getName(), request.usernames()));
    }

    /** The owner removes a collaborator, or a collaborator leaves (their own username). */
    @DeleteMapping(ApiPaths.AGENTS + "/{id}/members/{username}")
    public ApiResponse<Void> removeMember(@PathVariable String id, @PathVariable String username, Authentication auth) {
        agentService.removeMember(id, auth.getName(), username);
        return ApiResponse.empty();
    }

    @DeleteMapping(ApiPaths.AGENTS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        agentService.delete(id, auth.getName());
        return ApiResponse.empty();
    }
}
