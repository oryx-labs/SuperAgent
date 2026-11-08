package com.superagent.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.superagent.api.connector.ConnectorToolsDto;
import com.superagent.api.connector.ConnectorToolsDto.Param;
import com.superagent.api.connector.ConnectorToolsDto.Tool;
import com.superagent.core.connector.ConnectorTransport;
import com.superagent.core.connector.McpServerSpec;
import com.superagent.core.error.ForbiddenException;
import com.superagent.platform.integration.IntegrationService;
import com.superagent.runtime.mcp.McpClient;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A connector's capabilities as each employee sees them: the tools its MCP server lists for their own
 * authorization. Only {@code tools/list} is asked; no tool runs and no content is read. Connectors whose tools
 * SuperAgent serves itself list those instead.
 */
@Service
public class ConnectorToolsService {

    private static final Duration FRESH = Duration.ofMinutes(10);
    /** A stdio server started with {@code npx -y} downloads its package on first use. */
    private static final Duration STDIO_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final ConnectorService connectors;
    private final IntegrationService integrations;
    private final McpClient client;
    /** Lazily: the providers sit in modules whose services in turn use connectors. */
    private final ObjectProvider<McpToolProvider> providers;
    private final Map<String, ConnectorToolsDto> cache = new ConcurrentHashMap<>();

    public ConnectorToolsService(ConnectorService connectors, IntegrationService integrations, McpClient client,
                                 ObjectProvider<McpToolProvider> providers) {
        this.connectors = connectors;
        this.integrations = integrations;
        this.client = client;
        this.providers = providers;
    }

    public ConnectorToolsDto tools(String id, String viewer, boolean refresh) {
        String key = com.superagent.core.tenant.TenantContext.require() + "|" + id + "|" + viewer;
        ConnectorToolsDto cached = cache.get(key);
        if (!refresh && cached != null && cached.fetchedAt().plus(FRESH).isAfter(Instant.now())) {
            return cached;
        }
        ConnectorToolsDto fresh = read(id, viewer);
        if (fresh.error() == null) {
            cache.put(key, fresh);
        } else {
            cache.remove(key);
        }
        return fresh;
    }

    private ConnectorToolsDto read(String id, String viewer) {
        if (ConnectorService.BUILTIN_ID.equals(id)) {
            return own(id, providers.orderedStream().filter(p -> p.kinds().contains(McpGrants.Kind.CHAT)
                    || p.kinds().contains(McpGrants.Kind.TASK)).toList(), n -> true, null);
        }
        if (ConnectorService.FEISHU_ID.equals(id)) {
            return new ConnectorToolsDto(id, List.of(), Instant.now(), null,
                    "飞书消息本身不给 Agent 提供工具；授权飞书文档后，这里列出文档工具。");
        }
        if (ConnectorService.DINGTALK_ID.equals(id)) {
            if (!integrations.ready(IntegrationService.DINGTALK)) {
                throw new ForbiddenException("钉钉还没在系统设置里启用");
            }
            return own(id, providers.orderedStream().toList(), n -> n.startsWith("dingtalk_doc_"),
                    "公司的钉钉应用还没配置好文档权限，暂时没有可用的文档工具。");
        }
        if (ConnectorService.FEISHU_DOCS_ID.equals(id)) {
            if (!connectors.authorizedBy(id, viewer)) {
                throw new ForbiddenException("授权之后才能查看它的能力");
            }
            return own(id, providers.orderedStream().toList(), n -> n.startsWith("feishu_doc_"),
                    "公司的飞书应用还没配置好，暂时没有可用的文档工具。");
        }
        McpServerSpec spec = connectors.specFor(id, viewer)
                .orElseThrow(() -> new ForbiddenException("连接启用并授权之后才能查看它的能力"));
        try {
            List<JsonNode> listed = client.listTools(spec,
                    spec.transport() == ConnectorTransport.STDIO ? STDIO_TIMEOUT : HTTP_TIMEOUT);
            return new ConnectorToolsDto(id, listed.stream().map(ConnectorToolsService::tool).toList(), Instant.now(), null,
                    listed.isEmpty() ? "这个 MCP 服务没有列出任何工具。" : null);
        } catch (IOException e) {
            return new ConnectorToolsDto(id, List.of(), Instant.now(), e.getMessage(), null);
        }
    }

    /** Tools SuperAgent serves itself, deduplicated by name. */
    private static ConnectorToolsDto own(String id, Collection<McpToolProvider> from, Predicate<String> named, String emptyNote) {
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (McpToolProvider p : from) {
            p.tools().stream().map(t -> (JsonNode) t).filter(t -> named.test(t.path("name").asText()))
                    .forEach(t -> tools.putIfAbsent(t.path("name").asText(), tool(t)));
        }
        return new ConnectorToolsDto(id, List.copyOf(tools.values()), Instant.now(), null,
                tools.isEmpty() ? emptyNote : null);
    }

    static Tool tool(JsonNode t) {
        JsonNode schema = t.path("inputSchema");
        Set<String> required = new java.util.HashSet<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        List<Param> params = new ArrayList<>();
        schema.path("properties").fields().forEachRemaining(e -> params.add(new Param(e.getKey(),
                type(e.getValue()), required.contains(e.getKey()), text(e.getValue().path("description")))));
        JsonNode hints = t.path("annotations");
        String title = text(t.path("title"));
        return new Tool(t.path("name").asText(), title != null ? title : text(hints.path("title")),
                text(t.path("description")), bool(hints.path("readOnlyHint")), bool(hints.path("destructiveHint")), params);
    }

    private static String type(JsonNode prop) {
        JsonNode type = prop.path("type");
        if (type.isArray()) {
            List<String> types = new ArrayList<>();
            type.forEach(x -> types.add(x.asText()));
            return String.join(" | ", types);
        }
        return type.isTextual() ? type.asText() : prop.has("enum") ? "enum" : null;
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText().strip() : null;
    }

    private static Boolean bool(JsonNode n) {
        return n.isBoolean() ? n.asBoolean() : null;
    }
}
