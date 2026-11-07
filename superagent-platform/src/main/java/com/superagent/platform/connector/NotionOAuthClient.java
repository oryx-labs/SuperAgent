package com.superagent.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Each employee connects their own Notion workspace through the company's public Notion integration, choosing the
 * pages to share; the token runs Notion's official MCP server for their agents. Notion tokens may come with a
 * refresh token (newer integrations), else they don't expire.
 */
@Component
public class NotionOAuthClient implements OAuthProvider {

    public static final String KEY = "notion";
    static final String VERSION = "2022-06-28";

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public NotionOAuthClient(@Value("${superagent.oauth.notion.base-url:https://api.notion.com}") String base) {
        this.base = base.replaceAll("/+$", "");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String authorizeUrl(String clientId, String redirectUri, String state) {
        return base + "/v1/oauth/authorize?client_id=" + enc(clientId) + "&response_type=code&owner=user&redirect_uri="
                + enc(redirectUri) + "&state=" + enc(state);
    }

    @Override
    public Tokens exchange(String clientId, String clientSecret, String code, String redirectUri) throws IOException, InterruptedException {
        return token(clientId, clientSecret, json.createObjectNode().put("grant_type", "authorization_code")
                .put("code", code).put("redirect_uri", redirectUri), false);
    }

    @Override
    public Tokens refresh(String clientId, String clientSecret, String refreshToken) throws IOException, InterruptedException {
        return token(clientId, clientSecret, json.createObjectNode().put("grant_type", "refresh_token")
                .put("refresh_token", refreshToken), true);
    }

    @Override
    public String fetchLogin(String accessToken) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/users/me"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken)
                .header("Notion-Version", VERSION).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode me = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
        String owner = me.path("bot").path("owner").path("user").path("name").asText("");
        String workspace = me.path("bot").path("workspace_name").asText("");
        return !owner.isBlank() ? owner + (workspace.isBlank() ? "" : " · " + workspace)
                : !workspace.isBlank() ? workspace : "notion-user";
    }

    private Tokens token(String clientId, String clientSecret, JsonNode body, boolean refreshing) throws IOException, InterruptedException {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/json").header("Notion-Version", VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
        String access = node.path("access_token").asText("");
        if (r.statusCode() != 200 || access.isBlank()) {
            String message = "Notion token " + (refreshing ? "refresh" : "exchange") + " failed: "
                    + node.path("error_description").asText(node.path("message").asText(node.path("error").asText("HTTP " + r.statusCode())));
            if (refreshing && r.statusCode() < 500) {
                throw new TokenRejectedException(message);
            }
            throw new IOException(message);
        }
        String refresh = node.path("refresh_token").asText("");
        long expiresIn = node.path("expires_in").asLong(0);
        return new Tokens(access, refresh.isBlank() ? null : refresh, expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
