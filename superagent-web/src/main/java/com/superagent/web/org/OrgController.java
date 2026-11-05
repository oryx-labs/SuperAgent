package com.superagent.web.org;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.org.OrgDto;
import com.superagent.api.org.OrgRequests;
import com.superagent.platform.org.OrgService;
import com.superagent.platform.org.TeamAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Maintaining the organisation (admin-only: under /users and /admin, see SecurityConfig). */
@RestController
public class OrgController {

    private final OrgService org;
    private final TeamAdminService teamAdmins;

    public OrgController(OrgService org, TeamAdminService teamAdmins) {
        this.teamAdmins = teamAdmins;
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

    /** team path → its admins. */
    @GetMapping(ApiPaths.ORG + "/team-admins")
    public ApiResponse<Map<String, List<String>>> teamAdmins() {
        return ApiResponse.ok(teamAdmins.all());
    }

    @PutMapping(ApiPaths.ORG + "/team-admins")
    public ApiResponse<List<String>> saveTeamAdmins(@RequestBody OrgRequests.SaveTeamAdmins request) {
        return ApiResponse.ok(teamAdmins.set(request.team(), request.usernames()));
    }

    @PostMapping(ApiPaths.ORG + "/teams/rename")
    public ApiResponse<Void> renameTeam(@RequestBody OrgRequests.RenameTeam request) {
        org.renameTeam(request.from(), request.to());
        return ApiResponse.empty();
    }
}
