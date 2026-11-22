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
 * Atlassian Cloud's OAuth 2.0 (3LO) web flow, for the Jira Cloud connectors. Access tokens are short-lived and come with a
 * rotating refresh token ({@code offline_access}), so each refresh returns the next one to keep. Tokens handled here are
 * never logged.
 */
@Component
public class AtlassianOAuthClient implements OAuthProvider {

    public static final String KEY = "atlassian";
    /** {@code read:me} is what {@link #fetchLogin} (GET /me) needs; without it the sign-in fails right after the token exchange. */
    static final String SCOPE = "offline_access read:me read:jira-work write:jira-work read:jira-user";

    private final String authBase;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AtlassianOAuthClient(@Value("${superagent.oauth.atlassian.auth-url:https://auth.atlassian.com}") String authBase,
                                @Value("${superagent.oauth.atlassian.api-url:https://api.atlassian.com}") String apiBase) {
        this.authBase = authBase.replaceAll("/+$", "");
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String authorizeUrl(String clientId, String redirectUri, String state) {
        return authBase + "/authorize?audience=api.atlassian.com&client_id=" + enc(clientId) + "&scope=" + enc(SCOPE)
                + "&redirect_uri=" + enc(redirectUri) + "&state=" + enc(state) + "&response_type=code&prompt=consent";
    }

    @Override
    public Tokens exchange(String clientId, String clientSecret, String code, String redirectUri)
            throws IOException, InterruptedException {
        var body = mapper.createObjectNode().put("grant_type", "authorization_code").put("client_id", clientId)
                .put("client_secret", clientSecret).put("code", code).put("redirect_uri", redirectUri);
        return token(body.toString(), false);
    }

    @Override
    public Tokens refresh(String clientId, String clientSecret, String refreshToken) throws IOException, InterruptedException {
        var body = mapper.createObjectNode().put("grant_type", "refresh_token").put("client_id", clientId)
                .put("client_secret", clientSecret).put("refresh_token", refreshToken);
        return token(body.toString(), true);
    }

    @Override
    public String fetchLogin(String accessToken) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(apiBase + "/me"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Atlassian /me returned HTTP " + response.statusCode());
        }
        JsonNode node = mapper.readTree(response.body());
        String email = node.path("email").asText("");
        return !email.isBlank() ? email : node.path("name").asText("atlassian-user");
    }

    /** POSTs to the token endpoint; a 4xx on a refresh means the refresh token is no good any more. */
    private Tokens token(String json, boolean refreshing) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(authBase + "/oauth/token"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
        String access = node.path("access_token").asText("");
        if (response.statusCode() != 200 || access.isBlank()) {
            String detail = node.hasNonNull("error_description") ? node.get("error_description").asText()
                    : node.hasNonNull("error") ? node.get("error").asText() : "HTTP " + response.statusCode();
            String message = "Atlassian token " + (refreshing ? "refresh" : "exchange") + " failed: " + detail;
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
