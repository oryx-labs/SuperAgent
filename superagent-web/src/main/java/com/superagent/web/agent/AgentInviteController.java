package com.superagent.web.agent;

import com.superagent.agent.AgentInviteService;
import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.agent.AgentInviteDto;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Invitations an agent proposed in its conversation; the owner confirms or cancels them on their card. */
@RestController
public class AgentInviteController {

    private final AgentInviteService invites;

    public AgentInviteController(AgentInviteService invites) {
        this.invites = invites;
    }

    @GetMapping(ApiPaths.AGENTS + "/{id}/invites")
    public ApiResponse<List<AgentInviteDto>> list(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(invites.list(id, auth.getName()));
    }

    @PostMapping(ApiPaths.AGENTS + "/{id}/invites/{inviteId}/confirm")
    public ApiResponse<AgentInviteDto> confirm(@PathVariable String id, @PathVariable String inviteId, Authentication auth) {
        return ApiResponse.ok(invites.confirm(id, inviteId, auth.getName()));
    }

    @PostMapping(ApiPaths.AGENTS + "/{id}/invites/{inviteId}/cancel")
    public ApiResponse<AgentInviteDto> cancel(@PathVariable String id, @PathVariable String inviteId, Authentication auth) {
        return ApiResponse.ok(invites.cancel(id, inviteId, auth.getName()));
    }
}
