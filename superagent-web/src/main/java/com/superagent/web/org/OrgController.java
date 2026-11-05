package com.superagent.web.org;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.org.OrgDto;
import com.superagent.api.org.OrgRequests;
import com.superagent.platform.org.OrgService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Maintaining the organisation (admin-only: under /users and /admin, see SecurityConfig). */
@RestController
public class OrgController {

    private final OrgService org;

    public OrgController(OrgService org) {
        this.org = org;
    }

    @GetMapping(ApiPaths.ORG)
    public ApiResponse<OrgDto> overview() {
        return ApiResponse.ok(org.overview());
    }

    @PutMapping(ApiPaths.USERS + "/{id}/org")
    public ApiResponse<OrgDto.Member> save(@PathVariable String id, @RequestBody OrgRequests.SaveMember request) {
        return ApiResponse.ok(org.save(id, request.manager(), request.teams()));
    }

    @PostMapping(ApiPaths.ORG + "/teams/rename")
    public ApiResponse<Void> renameTeam(@RequestBody OrgRequests.RenameTeam request) {
        org.renameTeam(request.from(), request.to());
        return ApiResponse.empty();
    }
}
