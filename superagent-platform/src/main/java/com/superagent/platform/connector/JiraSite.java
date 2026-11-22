package com.superagent.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * A company's own Jira (Server / Data Center). An employee signs in with a personal access token; it is checked against
 * the site before it is saved, and only ever sent to the address the administrator set.
 */
@Component
public class JiraSite {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    public static final String CLOUD = "jira-cloud";
    public static final String DC = "jira-dc";
    /** Jira connectors carry their product in the id, so nothing beyond the generic connector record is needed. */
    static final String CLOUD_PREFIX = CLOUD + "-";
    static final String DC_PREFIX = DC + "-";
    /** A Data Center connector's endpoint: the site, then the command that runs the MCP server for it. */
    private static final String SITE_PREFIX = "JIRA_URL=";

    /** jira-cloud | jira-dc for a Jira connector's id, else null. */
    static String productOf(String connectorId) {
        return connectorId.startsWith(CLOUD_PREFIX) ? CLOUD : connectorId.startsWith(DC_PREFIX) ? DC : null;
    }

    static String endpointFor(String site, String command) {
        return SITE_PREFIX + site + " " + command;
    }

    /** The site a Data Center connector's endpoint names; null for anything else. */
    static String siteOf(String endpoint) {
        if (endpoint == null || !endpoint.startsWith(SITE_PREFIX)) {
            return null;
        }
        int end = endpoint.indexOf(' ');
        return endpoint.substring(SITE_PREFIX.length(), end < 0 ? endpoint.length() : end);
    }

    /** The address as entered, checked: http(s), no trailing slash. */
    static String normalize(String site) {
        String s = site == null ? "" : site.strip();
        if (!s.matches("(?i)https?://[^\\s/]+(/\\S*)?")) {
            throw new IllegalArgumentException("Jira 地址要以 http:// 或 https:// 开头，例如 https://jira.example.com");
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** The environment mcp-atlassian reads, for one employee's token. */
    static Map<String, String> env(String site, String token) {
        return Map.of("JIRA_URL", site, "JIRA_PERSONAL_TOKEN", token);
    }

    /** Where an employee makes the token this site takes. */
    static String tokenPage(String site) {
        return site + "/plugins/personalaccesstokens/usertokens.action";
    }

    /** Signs in as the employee and returns their Jira username; throws {@link IllegalArgumentException} with what to fix. */
    public String verify(String site, String token) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(site + "/rest/api/2/myself")).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalArgumentException("连不上 Jira（" + site + "）：" + e.getMessage());
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
        if (me == null || !me.isObject() || me.path("name").asText("").isBlank() && me.path("displayName").asText("").isBlank()) {
            throw new IllegalArgumentException(resp.statusCode() == 200 || resp.statusCode() == 401 || resp.statusCode() == 403
                    ? "个人访问令牌无效或已过期，Jira 没有认出你"
                    : "Jira 返回 HTTP " + resp.statusCode() + "，请确认地址是否正确");
        }
        String who = me.path("name").asText("");
        return who.isBlank() ? me.path("displayName").asText("") : who;
    }
}
