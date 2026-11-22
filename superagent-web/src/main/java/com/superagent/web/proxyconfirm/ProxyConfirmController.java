package com.superagent.web.proxyconfirm;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.proxyconfirm.ProxyConfirmDtos;
import com.superagent.orchestrator.ProxyConfirmService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** "Confirm for me": a user's own rules and records; the admin audit list is read-only. */
@RestController
public class ProxyConfirmController {

    private final ProxyConfirmService service;

    public ProxyConfirmController(ProxyConfirmService service) {
        this.service = service;
    }

    @GetMapping(ApiPaths.PROXY_CONFIRM + "/rule")
    public ApiResponse<ProxyConfirmDtos.RuleDto> rule(@RequestParam(required = false) String projectId, Authentication auth) {
        return ApiResponse.ok(service.rule(auth.getName(), projectId));
    }

    @PutMapping(ApiPaths.PROXY_CONFIRM + "/rule")
    public ApiResponse<ProxyConfirmDtos.RuleDto> save(@RequestParam(required = false) String projectId,
                                                      @RequestBody ProxyConfirmDtos.SaveRule request, Authentication auth) {
        return ApiResponse.ok(service.save(auth.getName(), projectId, request));
    }

    /** A project goes back to the global rule. */
    @DeleteMapping(ApiPaths.PROXY_CONFIRM + "/rule")
    public ApiResponse<ProxyConfirmDtos.RuleDto> inherit(@RequestParam(required = false) String projectId, Authentication auth) {
        return ApiResponse.ok(service.inherit(auth.getName(), projectId));
    }

    @GetMapping(ApiPaths.PROXY_CONFIRM + "/records")
    public ApiResponse<List<ProxyConfirmDtos.RecordDto>> mine(Authentication auth) {
        return ApiResponse.ok(service.mine(auth.getName()));
    }

    @PostMapping(ApiPaths.PROXY_CONFIRM + "/records/{id}/revoke")
    public ApiResponse<ProxyConfirmDtos.RecordDto> revoke(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(service.revoke(auth.getName(), id));
    }

    @GetMapping(ApiPaths.ADMIN_PROXY_CONFIRM)
    public ApiResponse<List<ProxyConfirmDtos.RecordDto>> audit(@RequestParam(required = false) String user,
                                                               @RequestParam(required = false) String type,
                                                               @RequestParam(required = false) Instant from,
                                                               @RequestParam(required = false) Instant to) {
        return ApiResponse.ok(service.audit(user, type, from, to));
    }
}
