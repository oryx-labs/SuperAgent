package com.superagent.web.connector;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.ConnectorToolsDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.platform.connector.ConnectorService;
import com.superagent.platform.connector.ConnectorToolsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Connectors. Everyone lists them and manages their own authorization ({@code /token}, {@code /bind-code});
 * adding, editing and removing them is for administrators (enforced in the security config).
 */
@RestController
public class ConnectorController {

    private final ConnectorService connectorService;
    private final ConnectorToolsService toolsService;

    public ConnectorController(ConnectorService connectorService, ConnectorToolsService toolsService) {
        this.connectorService = connectorService;
        this.toolsService = toolsService;
    }

    /** The tools the connector's MCP server lists for the viewer's own authorization. */
    @GetMapping(ApiPaths.CONNECTORS + "/{id}/tools")
    public ApiResponse<ConnectorToolsDto> tools(@PathVariable String id,
                                                @RequestParam(defaultValue = "false") boolean refresh, Authentication auth) {
        return ApiResponse.ok(toolsService.tools(id, auth.getName(), refresh));
    }

    @GetMapping(ApiPaths.CONNECTORS)
    public ApiResponse<List<ConnectorDto>> list(Authentication auth) {
        return ApiResponse.ok(connectorService.list(auth.getName(), isAdmin(auth)));
    }

    @PostMapping(ApiPaths.CONNECTORS)
    public ApiResponse<ConnectorDto> create(@RequestBody SaveConnectorRequest request, Authentication auth) {
        return ApiResponse.ok(connectorService.create(request, auth.getName()));
    }

    @PutMapping(ApiPaths.CONNECTORS + "/{id}")
    public ApiResponse<ConnectorDto> update(@PathVariable String id, @RequestBody SaveConnectorRequest request,
                                            Authentication auth) {
        return ApiResponse.ok(connectorService.update(id, request, auth.getName()));
    }

    /** On or off: system connectors allow only this. */
    @PutMapping(ApiPaths.CONNECTORS + "/{id}/enabled")
    public ApiResponse<ConnectorDto> setEnabled(@PathVariable String id, @RequestBody java.util.Map<String, Boolean> body,
                                                Authentication auth) {
        return ApiResponse.ok(connectorService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")), auth.getName()));
    }

    @DeleteMapping(ApiPaths.CONNECTORS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        connectorService.delete(id);
        return ApiResponse.empty();
    }

    @PutMapping(ApiPaths.CONNECTORS + "/{id}/token")
    public ApiResponse<ConnectorDto> authorize(@PathVariable String id, @RequestBody Map<String, String> body,
                                               Authentication auth) {
        return ApiResponse.ok(connectorService.authorize(id, auth.getName(), body.get("token"), isAdmin(auth)));
    }

    @DeleteMapping(ApiPaths.CONNECTORS + "/{id}/token")
    public ApiResponse<ConnectorDto> revoke(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(connectorService.revoke(id, auth.getName(), isAdmin(auth)));
    }

    @PostMapping(ApiPaths.CONNECTORS + "/{id}/bind-code")
    public ApiResponse<ConnectorDto> bindCode(@PathVariable String id, Authentication auth) {
        return ApiResponse.ok(connectorService.newBindCode(id, auth.getName(), isAdmin(auth)));
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
