package com.superagent.web.superagent;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.chat.SendMessageRequest;
import com.superagent.api.superagent.SuperAgentStateDto;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping(ApiPaths.SUPER_AGENT + "/plans/{planId}/approve")
    public ApiResponse<SuperAgentStateDto> approve(@PathVariable String planId, Authentication auth) {
        return ApiResponse.ok(service.approvePlan(auth.getName(), planId));
    }
}
