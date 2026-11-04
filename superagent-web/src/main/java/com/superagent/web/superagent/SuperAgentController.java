package com.superagent.web.superagent;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.SendMessageRequest;
import com.superagent.api.superagent.SuperAgentStateDto;
import com.superagent.api.superagent.SuperAgentWorkDto;
import com.superagent.api.superagent.SuperAgentWorkEventDto;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The signed-in user's SuperAgent. Messages go into its inbox and come back later as replies in
 * its chat session (read through the ordinary session endpoints); this API covers the inbox
 * state, dropping a message in, and approving a plan.
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

    @PostMapping(ApiPaths.SUPER_AGENT + "/messages")
    public ApiResponse<SuperAgentStateDto> submit(@RequestBody SendMessageRequest request, Authentication auth) {
        return ApiResponse.ok(service.submit(auth.getName(), request.content(), request.images()));
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
