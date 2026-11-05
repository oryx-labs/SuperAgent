package com.superagent.web.automation;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.automation.AutomationDetailDto;
import com.superagent.api.automation.AutomationDto;
import com.superagent.api.automation.AutomationRunDto;
import com.superagent.api.automation.SaveAutomationRequest;
import com.superagent.api.automation.TriggerAutomationRequest;
import com.superagent.task.automation.AutomationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Automation endpoints. Everything needs a signed-in user except {@code POST /{id}/trigger},
 * which external systems call with the automation's own token in {@code X-Automation-Token}
 * (opened in SecurityConfig; the token is checked in {@link AutomationService#trigger}).
 */
@RestController
public class AutomationController {

    static final String TOKEN_HEADER = "X-Automation-Token";

    private final AutomationService service;

    public AutomationController(AutomationService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.AUTOMATIONS)
    public ApiResponse<List<AutomationDto>> list(Authentication auth) {
        return ApiResponse.ok(service.list(auth.getName(), isAdmin(auth)));
    }

    @PostMapping(ApiPaths.AUTOMATIONS)
    public ApiResponse<AutomationDto> create(@RequestBody SaveAutomationRequest request, Authentication auth) {
        return ApiResponse.ok(service.create(request, auth.getName(), "manual"));
    }

    /** Next firings of a draft schedule, for the form's live preview. */
    @PostMapping(ApiPaths.AUTOMATIONS + "/preview")
    public ApiResponse<List<Instant>> preview(@RequestBody SaveAutomationRequest request) {
        return ApiResponse.ok(service.preview(request));
    }

    @GetMapping(ApiPaths.AUTOMATIONS + "/{id}")
    public ApiResponse<AutomationDetailDto> detail(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(service.detail(id, auth.getName(), isAdmin(auth)));
    }

    @PutMapping(ApiPaths.AUTOMATIONS + "/{id}")
    public ApiResponse<AutomationDto> update(@PathVariable String id, @RequestBody SaveAutomationRequest request,
                                             Authentication auth) {
        return ApiResponse.ok(service.update(id, request, auth.getName(), isAdmin(auth)));
    }

    @PostMapping(ApiPaths.AUTOMATIONS + "/{id}/enabled")
    public ApiResponse<AutomationDto> setEnabled(@PathVariable String id, @RequestParam boolean value,
                                                 Authentication auth) {
        return ApiResponse.ok(service.setEnabled(id, value, auth.getName(), isAdmin(auth)));
    }

    @PostMapping(ApiPaths.AUTOMATIONS + "/{id}/run")
    public ApiResponse<AutomationRunDto> runNow(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(service.runNow(id, auth.getName(), isAdmin(auth)));
    }

    @PostMapping(ApiPaths.AUTOMATIONS + "/{id}/token")
    public ApiResponse<AutomationDto> regenerateToken(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(service.regenerateToken(id, auth.getName(), isAdmin(auth)));
    }

    @DeleteMapping(ApiPaths.AUTOMATIONS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        service.delete(id, auth.getName(), isAdmin(auth));
        return ApiResponse.empty();
    }

    @PostMapping(ApiPaths.AUTOMATIONS + "/{id}/trigger")
    public ApiResponse<AutomationRunDto> trigger(@PathVariable String id,
                                                 @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                 @RequestBody(required = false) TriggerAutomationRequest request) {
        return ApiResponse.ok(service.trigger(id, token, request == null ? null : request.input()));
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
