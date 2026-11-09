package com.superagent.web.org;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.auth.OrgRequests;
import com.superagent.platform.tenant.OrganizationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Registering an organization, before anyone is signed in. */
@RestController
public class OrgController {

    private final OrganizationService orgs;

    public OrgController(OrganizationService orgs) {
        this.orgs = orgs;
    }

    /** Whether the sign-in page offers registering an organization. */
    @org.springframework.web.bind.annotation.GetMapping(ApiPaths.ORGS + "/registration")
    public ApiResponse<java.util.Map<String, Boolean>> registration() {
        return ApiResponse.ok(java.util.Map.of("open", orgs.registrationOpen()));
    }

    @PostMapping(ApiPaths.ORGS)
    public ApiResponse<OrgRequests.Registered> register(@RequestBody OrgRequests.Register request) {
        OrganizationService.Registered r = orgs.register(request.name(), request.englishName());
        return ApiResponse.ok(new OrgRequests.Registered(r.org(), r.name(), r.username(), r.password()));
    }
}
