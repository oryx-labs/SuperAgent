package com.superagent.web.connector;

import com.superagent.api.ApiPaths;
import com.superagent.api.ApiResponse;
import com.superagent.api.connector.ConnectorDto;
import com.superagent.api.connector.SaveConnectorRequest;
import com.superagent.platform.connector.ConnectorService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** MCP connector registry endpoints: CRUD plus connect/disconnect toggles. */
@RestController
public class ConnectorController {

    private final ConnectorService connectorService;

    public ConnectorController(ConnectorService connectorService) {
        this.connectorService = connectorService;
    }

    @GetMapping(ApiPaths.CONNECTORS)
    public ApiResponse<List<ConnectorDto>> list() {
        return ApiResponse.ok(connectorService.list());
    }

    @PostMapping(ApiPaths.CONNECTORS)
    public ApiResponse<ConnectorDto> create(@RequestBody SaveConnectorRequest request) {
        return ApiResponse.ok(connectorService.create(request));
    }

    @PutMapping(ApiPaths.CONNECTORS + "/{id}")
    public ApiResponse<ConnectorDto> update(@PathVariable String id, @RequestBody SaveConnectorRequest request) {
        return ApiResponse.ok(connectorService.update(id, request));
    }

    @DeleteMapping(ApiPaths.CONNECTORS + "/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        connectorService.delete(id);
        return ApiResponse.empty();
    }

    @PostMapping(ApiPaths.CONNECTORS + "/{id}/connect")
    public ApiResponse<ConnectorDto> connect(@PathVariable String id) {
        return ApiResponse.ok(connectorService.connect(id));
    }

    @PostMapping(ApiPaths.CONNECTORS + "/{id}/disconnect")
    public ApiResponse<ConnectorDto> disconnect(@PathVariable String id) {
        return ApiResponse.ok(connectorService.disconnect(id));
    }
}
