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

/**
 * Gitee's OAuth web flow and user lookup. Its access tokens last a day ({@code expires_in}) and come with a refresh
 * token, so the credential is renewed before runs use it. Tokens handled here are never logged.
 */
@Component
public class GiteeOAuthClient implements OAuthProvider {

    public static final String KEY = "gitee";
    /** Read the account; repositories, pull requests, issues and their comments — what the Gitee MCP server works with. */
    static final String SCOPE = "user_info projects pull_requests issues notes";

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public GiteeOAuthClient(@Value("${superagent.oauth.gitee.base-url:https://gitee.com}") String base) {
        this.base = base.replaceAll("/+$", "");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String authorizeUrl(String clientId, String redirectUri, String state) {
        return base + "/oauth/authorize?client_id=" + enc(clientId) + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + enc(SCOPE) + "&state=" + enc(state);
    }

    @Override
    public Tokens exchange(String clientId, String clientSecret, String code, String redirectUri)
            throws IOException, InterruptedException {
        return token("grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri) + "&client_secret=" + enc(clientSecret), false);
    }

    @Override
    public Tokens refresh(String clientId, String clientSecret, String refreshToken) throws IOException, InterruptedException {
        return token("grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret), true);
    }

    @Override
    public String fetchLogin(String accessToken) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/v5/user?access_token=" + enc(accessToken)))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Gitee /user returned HTTP " + response.statusCode());
        }
        JsonNode node = mapper.readTree(response.body());
        return node.hasNonNull("login") ? node.get("login").asText() : "gitee-user";
    }

    /** POSTs to the token endpoint; a 4xx on a refresh means the refresh token is no good any more. */
    private Tokens token(String form, boolean refreshing) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        String access = node.path("access_token").asText("");
        if (response.statusCode() != 200 || access.isBlank()) {
            String detail = node.hasNonNull("error_description") ? node.get("error_description").asText()
                    : node.hasNonNull("error") ? node.get("error").asText() : "HTTP " + response.statusCode();
            String message = "Gitee token " + (refreshing ? "refresh" : "exchange") + " failed: " + detail;
            if (refreshing && response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new TokenRejectedException(message);
            }
            throw new IOException(message);
        }
        String refresh = node.path("refresh_token").asText("");
        long expiresIn = node.path("expires_in").asLong(0);
        return new Tokens(access, refresh.isBlank() ? null : refresh, expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
