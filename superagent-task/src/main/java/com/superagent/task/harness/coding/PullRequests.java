package com.superagent.task.harness.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Opens the pull request (GitHub) or merge request (GitLab) for a pushed branch, as the task owner. */
final class PullRequests {

    static final String GITHUB = "github";
    static final String GITLAB = "gitlab";

    // Never follow a redirect with the token on: it would carry it to wherever the response points.
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Which of the owner's tokens may go to this remote: GitHub's only to github.com, GitLab's only to the exact site
     * that token was verified on; anything else gets none. Only over https.
     */
    static String connectorFor(String repo, String gitlabSite) {
        if (repo == null || !repo.strip().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            return null;
        }
        String host = Workspace.host(repo);
        if (host.equals("github.com")) {
            return GITHUB;
        }
        return gitlabSite != null && gitlabSite.equals(siteOf(repo)) ? GITLAB : null;
    }

    /** {@code https://host[:port]} of a remote. */
    static String siteOf(String repo) {
        URI u = URI.create(repo.strip());
        return "https://" + u.getHost().toLowerCase(java.util.Locale.ROOT) + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /** Returns the request's address; {@code connector} is what {@link #connectorFor} allowed for this remote. */
    String open(String connector, String repo, String token, String branch, String base, String title, String body)
            throws IOException {
        String path = Workspace.path(repo);
        return GITHUB.equals(connector) ? github(path, token, branch, base, title, body)
                : gitlab(siteOf(repo), path, token, branch, base, title, body);
    }

    private String github(String path, String token, String branch, String base, String title, String body) throws IOException {
        String payload = json.writeValueAsString(Map.of("title", title, "head", branch, "base", base, "body", body));
        JsonNode r = send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + path + "/pulls"))
                .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build());
        return r.path("html_url").asText();
    }

    private String gitlab(String site, String path, String token, String branch, String base, String title, String body)
            throws IOException {
        String project = URLEncoder.encode(path, StandardCharsets.UTF_8);
        String payload = json.writeValueAsString(Map.of("title", title, "source_branch", branch, "target_branch", base,
                "description", body, "remove_source_branch", true));
        JsonNode r = send(HttpRequest.newBuilder(URI.create(site + "/api/v4/projects/" + project + "/merge_requests"))
                .header("PRIVATE-TOKEN", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build());
        return r.path("web_url").asText();
    }

    private JsonNode send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode() + "：" + Git.tail(resp.body(), 400));
            }
            return json.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
