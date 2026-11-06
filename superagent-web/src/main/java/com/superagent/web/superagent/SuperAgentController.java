package com.superagent.web.superagent;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.MessageDto;
import com.superagent.api.superagent.SuperAgentStateDto;
import com.superagent.api.superagent.SuperAgentWorkDto;
import com.superagent.api.superagent.SuperAgentWorkEventDto;
import com.superagent.orchestrator.SuperAgentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The signed-in user's SuperAgent. Its chat runs through the ordinary session endpoints like any agent's; this API
 * covers its state (plan, team, background round), its background record, its work history, and approving a plan.
 */
@RestController
public class SuperAgentController {

    private final SuperAgentService service;

    public SuperAgentController(SuperAgentService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.SUPER_AGENT)
    public ApiResponse<SuperAgentStateDto> state(Authentication auth) {
        return ApiResponse.ok(service.state(auth.getName()));
    }

    /** Invitations the SuperAgent proposed in its chat (for the user's other agents), for their confirm cards. */
    @GetMapping(ApiPaths.SUPER_AGENT + "/invites")
    public ApiResponse<List<com.superagent.api.agent.AgentInviteDto>> invites(Authentication auth) {
        return ApiResponse.ok(service.invites(auth.getName()));
    }

    /** The background record: what reached the queue (task results, received messages) and how each round answered. */
    @GetMapping(ApiPaths.SUPER_AGENT + "/background")
    public ApiResponse<List<MessageDto>> background(@RequestParam(defaultValue = "200") int limit, Authentication auth) {
        return ApiResponse.ok(service.background(auth.getName(), Math.min(limit, 1000)));
    }

    /**
     * What the SuperAgent got done: the tasks it assigned, newest first. {@code status}: all | open |
     * finished | a single task status; {@code memberId} narrows to one team member.
     */
    @GetMapping(ApiPaths.SUPER_AGENT + "/work")
    public ApiResponse<List<SuperAgentWorkDto>> work(@RequestParam(defaultValue = "all") String status,
                                                     @RequestParam(required = false) String memberId,
                                                     @RequestParam(defaultValue = "100") int limit,
                                                     Authentication auth) {
        return ApiResponse.ok(service.work(auth.getName(), status, memberId, limit));
    }

    @GetMapping(ApiPaths.SUPER_AGENT + "/work/{taskId}/events")
    public ApiResponse<List<SuperAgentWorkEventDto>> workEvents(@PathVariable String taskId, Authentication auth) {
        return ApiResponse.ok(service.workEvents(auth.getName(), taskId));
    }

    @PostMapping(ApiPaths.SUPER_AGENT + "/plans/{planId}/approve")
    public ApiResponse<SuperAgentStateDto> approve(@PathVariable String planId, Authentication auth) {
        return ApiResponse.ok(service.approvePlan(auth.getName(), planId));
    }
}
