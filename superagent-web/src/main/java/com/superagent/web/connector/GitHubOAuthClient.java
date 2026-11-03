package com.superagent.web.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin client for GitHub's OAuth web flow and user lookup, over the JDK HTTP
 * client. Endpoints are GitHub's documented ones; tokens handled here are never
 * logged.
 */
@Component
public class GitHubOAuthClient {

    private static final String AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public String authorizeUrl(String clientId, String redirectUri, String scope, String state) {
        return AUTHORIZE_URL
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc(scope)
                + "&state=" + enc(state)
                + "&allow_signup=false";
    }

    /** Exchanges an authorization code for an access token. */
    public String exchangeCodeForToken(String clientId, String clientSecret, String code, String redirectUri)
            throws IOException, InterruptedException {
        String body = "client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode node = mapper.readTree(response.body());
        JsonNode token = node.get("access_token");
        if (token == null || token.asText().isBlank()) {
            String detail = node.hasNonNull("error_description")
                    ? node.get("error_description").asText() : "no access_token in response";
            throw new IOException("GitHub token exchange failed: " + detail);
        }
        return token.asText();
    }

    /** Returns the login (username) of the token's owner. */
    public String fetchLogin(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(USER_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub /user returned HTTP " + response.statusCode());
        }
        JsonNode node = mapper.readTree(response.body());
        return node.hasNonNull("login") ? node.get("login").asText() : "github-user";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
