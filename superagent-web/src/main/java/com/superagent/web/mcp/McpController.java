package com.superagent.web.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.tenant.TenantContext;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import com.superagent.runtime.mcp.McpToolProvider;
import com.superagent.runtime.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SuperAgent's own MCP server. It speaks the MCP Streamable-HTTP transport by hand: JSON-RPC 2.0
 * over a single {@code POST /mcp}, each request answered with one {@code application/json} response
 * (no server-initiated stream, so {@code GET /mcp} is 405).
 *
 * <p>The tools themselves come from {@link McpToolProvider}s in each module. Every call carries a
 * per-turn bearer token from {@link McpGrants}; its grant's kind (chat / task / SuperAgent round)
 * decides which providers' tools the run sees, and the user they act for.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpGrants grants;
    private final List<McpToolProvider> providers;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(McpGrants grants, List<McpToolProvider> providers) {
        this.grants = grants;
        this.providers = providers;
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build(); // no server-initiated SSE
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> rpc(@RequestBody JsonNode body,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        String credential = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
        McpGrants.Grant grant = grants.find(credential).orElse(null);
        if (grant == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String method = body.path("method").asText("");
        JsonNode id = body.get("id");
        // Notifications (no id) get acknowledged with 202 and no body.
        if (id == null || id.isNull()) {
            return ResponseEntity.accepted().build();
        }
        try {
            // The endpoint is open (the token authenticates), so no company was set for the request: the run's is.
            return TenantContext.call(grants.companyOf(grant), () -> switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "ping" -> ok(id, mapper.createObjectNode());
                case "tools/list" -> ok(id, toolsList(grant));
                case "tools/call" -> ok(id, callTool(grant, body.path("params")));
                default -> error(id, -32601, "Method not found: " + method);
            });
        } catch (McpToolException e) {
            return ok(id, textContent("错误：" + e.getMessage(), true)); // tool-level error, not a protocol error
        } catch (Exception e) {
            log.warn("mcp method {} failed", method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private List<McpToolProvider> providersFor(McpGrants.Grant grant) {
        return providers.stream().filter(p -> p.kinds().contains(grant.kind())).toList();
    }

    private ObjectNode toolsList(McpGrants.Grant grant) {
        ArrayNode tools = mapper.createArrayNode();
        providersFor(grant).forEach(p -> tools.addAll(p.tools()));
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode callTool(McpGrants.Grant grant, JsonNode params) {
        String name = params.path("name").asText("");
        McpToolProvider provider = providersFor(grant).stream().filter(p -> p.handles(name)).findFirst()
                .orElseThrow(() -> new McpToolException("Unknown tool: " + name));
        McpToolResult r = provider.call(grant, name, params.path("arguments"));
        return textContent(r.text(), r.error());
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "superagent");
        info.put("version", "0.1.0");
        return result;
    }

    private ObjectNode textContent(String text, boolean isError) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, JsonNode result) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        resp.set("result", result);
        return ResponseEntity.ok(resp);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return ResponseEntity.ok(resp);
    }
}
