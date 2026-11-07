package com.superagent.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.runtime.mcp.McpToolException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JSON over HTTP for the document tools; a refusal becomes a tool error the agent can read. */
final class DocApi {

    static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String platform;

    DocApi(String platform) {
        this.platform = platform;
    }

    JsonNode send(String method, String url, Map<String, String> headers, JsonNode body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        headers.forEach(b::header);
        b.header("Content-Type", "application/json; charset=utf-8");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new McpToolException("连不上" + platform + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("已取消");
        }
        JsonNode json;
        try {
            json = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        } catch (IOException e) {
            throw new McpToolException(platform + "返回了无法解析的内容（HTTP " + r.statusCode() + "）");
        }
        int code = json.path("code").isInt() ? json.path("code").asInt() : json.path("errcode").asInt(0);
        if (r.statusCode() >= 300 || code != 0) {
            String msg = json.path("msg").asText(json.path("message").asText(json.path("errmsg").asText("")));
            throw new McpToolException(platform + "拒绝了请求（HTTP " + r.statusCode() + (code != 0 ? "，错误码 " + code : "")
                    + "）" + (msg.isBlank() ? "" : "：" + msg));
        }
        return json;
    }
}
