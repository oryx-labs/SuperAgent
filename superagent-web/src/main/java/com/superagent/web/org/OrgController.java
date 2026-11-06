package com.superagent.web.org;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.org.OrgDto;
import com.superagent.platform.org.OrgService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The organisation, read-only (admin-only: under /admin, see SecurityConfig); it's kept by an external directory. */
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
}
