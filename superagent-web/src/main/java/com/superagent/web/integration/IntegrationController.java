package com.superagent.web.integration;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.integration.IntegrationDto;
import com.superagent.api.integration.SaveIntegrationRequest;
import com.superagent.platform.integration.IntegrationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** System Settings → integrations; under /api/v1/admin, so administrators only (security config). */
@RestController
public class IntegrationController {

    private final IntegrationService integrations;
    private final AppUrl appUrl;

    public IntegrationController(IntegrationService integrations, AppUrl appUrl) {
        this.integrations = integrations;
        this.appUrl = appUrl;
    }

    @GetMapping(ApiPaths.INTEGRATIONS)
    public ApiResponse<List<IntegrationDto>> list(HttpServletRequest request) {
        return ApiResponse.ok(integrations.list(appUrl.base(request)));
    }

    @PutMapping(ApiPaths.INTEGRATIONS + "/{key}")
    public ApiResponse<IntegrationDto> update(@PathVariable String key, @RequestBody SaveIntegrationRequest body,
                                              HttpServletRequest request) {
        return ApiResponse.ok(integrations.update(key, body, appUrl.base(request)));
    }
}
