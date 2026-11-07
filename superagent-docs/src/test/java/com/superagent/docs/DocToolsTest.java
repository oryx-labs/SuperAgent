package com.superagent.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.platform.connector.ConnectorService;
import com.superagent.platform.connector.FeishuDocsOAuthClient;
import com.superagent.platform.integration.IntegrationService;
import com.superagent.runtime.mcp.McpGrants;
import com.superagent.runtime.mcp.McpToolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The document tools against stand-ins for the Feishu and DingTalk APIs: who they act as, and what they send. */
class DocToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;

    /** A stand-in API answering by "METHOD path" (query left out); every call and body is kept. */
    private String serve(Function<String, String> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String uri = ex.getRequestURI().toString();
            calls.add(ex.getRequestMethod() + " " + uri + " " + ex.getRequestHeaders().getFirst("Authorization"));
            bodies.add(body.isBlank() ? json.createObjectNode() : json.readTree(body));
            byte[] out = answer.apply(ex.getRequestMethod() + " " + ex.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static McpGrants.Grant grant() {
        return new McpGrants.Grant("t", McpGrants.Kind.CHAT, "ada", null, "a1", "s1", null, null);
    }

    @Test
    void feishuToolsActAsTheEmployeeAndWriteMarkdownAsBlocks() throws Exception {
        String base = serve(req -> switch (req) {
            case "GET /open-apis/wiki/v2/spaces/get_node" -> "{\"code\":0,\"data\":{\"node\":{\"obj_type\":\"docx\",\"obj_token\":\"DocFromWiki01\"}}}";
            case "POST /open-apis/docx/v1/documents/blocks/convert" -> "{\"code\":0,\"data\":{\"first_level_block_ids\":[\"b1\"],"
                    + "\"blocks\":[{\"block_id\":\"b1\",\"block_type\":2,\"text\":{}}]}}";
            case "GET /open-apis/docx/v1/documents/DocFromWiki01/blocks/DocFromWiki01" -> "{\"code\":0,\"data\":{\"block\":{\"children\":[\"x\",\"y\"]}}}";
            case "GET /open-apis/drive/v1/files" -> "{\"code\":0,\"data\":{\"has_more\":true,\"next_page_token\":\"next1\","
                    + "\"files\":[{\"name\":\"周报\",\"type\":\"docx\",\"token\":\"Doc1\",\"url\":\"https://x/docx/Doc1\"},"
                    + "{\"name\":\"子目录\",\"type\":\"folder\",\"token\":\"Fold456\"}]}}";
            case "POST /open-apis/docx/v1/documents" -> "{\"code\":0,\"data\":{\"document\":{\"document_id\":\"NewDoc000001\"}}}";
            default -> "{\"code\":0,\"data\":{}}";
        });
        ConnectorService connectors = mock(ConnectorService.class);
        when(connectors.userToken(ConnectorService.FEISHU_DOCS_ID, "ada")).thenReturn(Optional.of("u-token"));
        FeishuDocsOAuthClient oauth = mock(FeishuDocsOAuthClient.class);
        when(oauth.base()).thenReturn(base);
        IntegrationService integrations = mock(IntegrationService.class);
        when(integrations.ready(IntegrationService.FEISHU)).thenReturn(true);
        when(integrations.value(IntegrationService.FEISHU, "domain")).thenReturn(Optional.empty());
        FeishuDocTools tools = new FeishuDocTools(connectors, oauth, integrations);
        assertThat(tools.tools()).extracting(t -> t.path("name").asText()).containsExactlyElementsOf(FeishuDocTools.NAMES);

        tools.call(grant(), "feishu_doc_replace", json.readTree(
                "{\"doc\":\"https://x.feishu.cn/wiki/WikiNode0001?from=a\",\"content\":\"# 新\"}"));
        assertThat(calls).allMatch(c -> c.endsWith("Bearer u-token"));
        assertThat(calls.get(0)).contains("get_node?token=WikiNode0001");
        assertThat(calls.get(2)).startsWith("DELETE /open-apis/docx/v1/documents/DocFromWiki01/blocks/DocFromWiki01/children/batch_delete");
        assertThat(bodies.get(2).path("end_index").asInt()).isEqualTo(2);
        assertThat(bodies.get(3).path("content_type").asText()).isEqualTo("markdown");
        assertThat(calls.get(4)).contains("/descendant");
        assertThat(bodies.get(4).path("children_id").get(0).asText()).isEqualTo("b1");

        calls.clear();
        assertThat(tools.call(grant(), "feishu_doc_create", json.readTree("{\"title\":\"周报\",\"content\":\"- a\"}")).toString())
                .contains("https://www.feishu.cn/docx/NewDoc000001");
        assertThat(calls.get(2)).contains("/documents/NewDoc000001/blocks/NewDoc000001/descendant");

        // The SuperAgent's chat lists and reads, but writing goes to its team; its background touches nothing.
        calls.clear();
        McpGrants.Grant superChat = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa", "s1", null, null);
        assertThat(tools.call(superChat, "feishu_doc_list", json.readTree("{\"folder\":\"https://x.feishu.cn/drive/folder/Fold123\"}"))
                .text()).contains("周报", "新版文档", "Fold456", "下一页 page_token：next1");
        assertThat(calls.get(0)).contains("/open-apis/drive/v1/files?").contains("folder_token=Fold123");
        assertThatThrownBy(() -> tools.call(superChat, "feishu_doc_create", json.readTree("{\"title\":\"x\",\"content\":\"y\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("start_task");
        McpGrants.Grant background = new McpGrants.Grant("t", McpGrants.Kind.SUPER, "ada", null, "sa", null, null, null);
        assertThatThrownBy(() -> tools.call(background, "feishu_doc_list", json.createObjectNode()))
                .isInstanceOf(McpToolException.class).hasMessageContaining("后台");

        when(connectors.userToken(ConnectorService.FEISHU_DOCS_ID, "ada")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.call(grant(), "feishu_doc_read", json.readTree("{\"doc\":\"DocFromWiki01\"}")))
                .isInstanceOf(McpToolException.class).hasMessageContaining("授权");
        when(integrations.ready(IntegrationService.FEISHU)).thenReturn(false);
        assertThat(tools.tools()).isEmpty();
    }

    @Test
    void dingtalkToolsNameTheEmployeeAsOperator() throws Exception {
        String base = serve(req -> switch (req) {
            case "POST /v1.0/oauth2/accessToken" -> "{\"accessToken\":\"app-t\",\"expireIn\":7200}";
            case "POST /topapi/v2/user/get" -> "{\"errcode\":0,\"result\":{\"unionid\":\"U-ada\"}}";
            case "POST /v1.0/doc/workspaces/ws1/docs" -> "{\"nodeId\":\"N1\",\"docKey\":\"K1\",\"url\":\"https://alidocs.dingtalk.com/i/nodes/N1\"}";
            case "GET /v1.0/doc/suites/documents/K1/blocks" -> "{\"result\":{\"data\":[{\"paragraph\":{\"text\":\"第一段\"}},"
                    + "{\"heading\":{\"text\":\"标题\"}}]}}";
            default -> "{}";
        });
        IntegrationService integrations = mock(IntegrationService.class);
        when(integrations.ready(IntegrationService.DINGTALK)).thenReturn(true);
        when(integrations.value(IntegrationService.DINGTALK, "clientId")).thenReturn(Optional.of("ding-app"));
        when(integrations.value(IntegrationService.DINGTALK, "clientSecret")).thenReturn(Optional.of("s"));
        EventSourceStore links = mock(EventSourceStore.class);
        when(links.find("ada", EventSource.DINGTALK)).thenReturn(Optional.of(new EventSource("ada", EventSource.DINGTALK,
                "ding-app", true, "user-7", null, null, null, null, null, null, Instant.now(), Instant.now())));
        DingTalkDocTools tools = new DingTalkDocTools(integrations, links, base, base);

        assertThat(tools.call(grant(), "dingtalk_doc_create", json.readTree(
                "{\"workspace_id\":\"ws1\",\"name\":\"周报\",\"content\":\"# 本周\"}")).toString()).contains("alidocs.dingtalk.com/i/nodes/N1");
        assertThat(bodies.get(1).path("userid").asText()).isEqualTo("user-7");
        assertThat(bodies.get(2).path("operatorId").asText()).isEqualTo("U-ada");
        assertThat(calls.get(3)).startsWith("POST /v1.0/doc/suites/documents/K1/overwriteContent?operatorId=U-ada");
        assertThat(bodies.get(3).path("content").asText()).isEqualTo("# 本周");

        assertThat(tools.call(grant(), "dingtalk_doc_read", json.readTree("{\"doc\":\"K1\"}")).toString()).contains("第一段", "标题");
        assertThat(calls.stream().filter(c -> c.contains("accessToken")).count()).isEqualTo(1); // the app token is reused

        when(links.find("ada", EventSource.DINGTALK)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> tools.call(grant(), "dingtalk_doc_spaces", json.createObjectNode()))
                .isInstanceOf(McpToolException.class).hasMessageContaining("关联");
    }
}
