package com.superagent.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.platform.integration.IntegrationService;
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

/**
 * Each employee signs in to Feishu (or Lark) with the company's Feishu app, so their agents read and write the
 * documents that employee can, as that employee. Same app as the messaging integration: no second app to set up,
 * only its redirect URL. Tokens last two hours and renew with the refresh token ({@code offline_access}).
 */
@Component
public class FeishuDocsOAuthClient implements OAuthProvider {

    public static final String KEY = "feishu-docs";
    /** Documents (read/write), Drive (create in folders), wiki nodes, search, and staying signed in. */
    static final String SCOPE = "docx:document drive:drive wiki:wiki:readonly search:docs:read offline_access";

    private final IntegrationService integrations;
    private final String feishu;
    private final String lark;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public FeishuDocsOAuthClient(IntegrationService integrations,
                                 @Value("${superagent.oauth.feishu.base-url:https://open.feishu.cn}") String feishu,
                                 @Value("${superagent.oauth.lark.base-url:https://open.larksuite.com}") String lark) {
        this.integrations = integrations;
        this.feishu = feishu.replaceAll("/+$", "");
        this.lark = lark.replaceAll("/+$", "");
    }

    /** The open platform for the edition the company's app is on. */
    public String base() {
        return IntegrationService.FEISHU_DOMAIN_LARK.equals(integrations.value(IntegrationService.FEISHU, "domain").orElse(null))
                ? lark : feishu;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String integration() {
        return IntegrationService.FEISHU;
    }

    @Override
    public String clientIdField() {
        return "appId";
    }

    @Override
    public String clientSecretField() {
        return "appSecret";
    }

    @Override
    public String authorizeUrl(String clientId, String redirectUri, String state) {
        return base() + "/open-apis/authen/v1/authorize?client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + enc(SCOPE) + "&state=" + enc(state);
    }

    @Override
    public Tokens exchange(String clientId, String clientSecret, String code, String redirectUri) throws IOException, InterruptedException {
        return token(json.createObjectNode().put("grant_type", "authorization_code").put("client_id", clientId)
                .put("client_secret", clientSecret).put("code", code).put("redirect_uri", redirectUri), false);
    }

    @Override
    public Tokens refresh(String clientId, String clientSecret, String refreshToken) throws IOException, InterruptedException {
        return token(json.createObjectNode().put("grant_type", "refresh_token").put("client_id", clientId)
                .put("client_secret", clientSecret).put("refresh_token", refreshToken), true);
    }

    @Override
    public String fetchLogin(String accessToken) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/open-apis/authen/v1/user_info"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + accessToken).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
        String name = body.path("data").path("name").asText("");
        return name.isBlank() ? "feishu-user" : name;
    }

    private Tokens token(JsonNode body, boolean refreshing) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/open-apis/authen/v2/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
        String access = node.path("access_token").asText("");
        if (r.statusCode() != 200 || node.path("code").asInt(0) != 0 || access.isBlank()) {
            String message = "Feishu token " + (refreshing ? "refresh" : "exchange") + " failed: "
                    + node.path("error_description").asText(node.path("msg").asText("HTTP " + r.statusCode()));
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
