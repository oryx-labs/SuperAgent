package com.superagent.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * The company's Confluence site, self-hosted (Server / Data Center) or Atlassian Cloud. Which one is told by the
 * address, the same way the MCP server (mcp-atlassian) tells it: Cloud signs in with the employee's email and an API
 * token, self-hosted with a personal access token. A token is checked against the site before it is saved.
 */
@Component
public class ConfluenceSite {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    /** Hosts mcp-atlassian treats as Atlassian Cloud. */
    static boolean isCloud(String site) {
        String host = host(site);
        return host.endsWith(".atlassian.net") || host.endsWith(".atlassian.com") || host.endsWith(".jira.com");
    }

    /** The address as entered, checked: http(s), no trailing slash; a Cloud site down to its origin. */
    static String normalize(String site) {
        String s = site == null ? "" : site.strip();
        if (!s.matches("(?i)https?://[^\\s/]+(/\\S*)?")) {
            throw new IllegalArgumentException("Confluence 地址要以 http:// 或 https:// 开头，例如 https://wiki.example.com");
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (isCloud(s)) {
            URI u = URI.create(s);
            s = u.getScheme() + "://" + u.getHost();
        }
        return s;
    }

    /** What the MCP server is pointed at: a Cloud site's Confluence lives under /wiki. */
    static String mcpUrl(String site) {
        String s = normalize(site);
        return isCloud(s) ? s + "/wiki" : s;
    }

    /** The environment mcp-atlassian reads, for one employee's credential. */
    static Map<String, String> env(String site, String account, String token) {
        return isCloud(site)
                ? Map.of("CONFLUENCE_URL", mcpUrl(site), "CONFLUENCE_USERNAME", account == null ? "" : account,
                "CONFLUENCE_API_TOKEN", token)
                : Map.of("CONFLUENCE_URL", mcpUrl(site), "CONFLUENCE_PERSONAL_TOKEN", token);
    }

    /** Where an employee makes the token this site takes. */
    static String tokenPage(String site) {
        return isCloud(site) ? "https://id.atlassian.com/manage-profile/security/api-tokens"
                : normalize(site) + "/plugins/personalaccesstokens/usertokens.action";
    }

    /**
     * Signs in as the employee and returns who they are on the site (Cloud: the email they signed in with; self-hosted:
     * their username). Throws {@link IllegalArgumentException} with what to fix when the site turns them away.
     */
    public String verify(String site, String account, String token) {
        boolean cloud = isCloud(site);
        if (cloud && (account == null || !account.contains("@"))) {
            throw new IllegalArgumentException("Confluence Cloud 要填你登录 Atlassian 的邮箱");
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(mcpUrl(site) + "/rest/api/user/current"))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
        req.header("Authorization", cloud
                ? "Basic " + Base64.getEncoder().encodeToString((account.strip() + ":" + token).getBytes(StandardCharsets.UTF_8))
                : "Bearer " + token);
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalArgumentException("连不上 Confluence（" + mcpUrl(site) + "）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
        JsonNode me = null;
        if (resp.statusCode() == 200) {
            try {
                me = json.readTree(resp.body());
            } catch (IOException ignored) {
                // not JSON: a login page in front of the site, handled below
            }
        }
        // Cloud answers a bad sign-in with the anonymous user rather than an error.
        if (me == null || !me.isObject() || "anonymous".equals(me.path("type").asText())) {
            throw new IllegalArgumentException(resp.statusCode() == 200 || resp.statusCode() == 401 || resp.statusCode() == 403
                    ? (cloud ? "邮箱或 API 令牌不对，Confluence 没有认出你" : "个人访问令牌无效或已过期，Confluence 没有认出你")
                    : "Confluence 返回 HTTP " + resp.statusCode() + "，请确认地址是否正确");
        }
        if (cloud) {
            return account.strip().toLowerCase(Locale.ROOT);
        }
        String who = me.path("username").asText("");
        return who.isBlank() ? me.path("displayName").asText("") : who;
    }

    private static String host(String site) {
        try {
            String h = URI.create(site.strip()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | NullPointerException e) {
            return "";
        }
    }
}
