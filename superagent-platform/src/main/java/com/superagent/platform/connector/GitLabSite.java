package com.superagent.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * An employee's GitLab: the company's own or gitlab.com. Its address is given with the token and checked against it,
 * then kept with the authorization, so the token is only ever sent back to that site.
 */
final class GitLabSite {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build(); // never carry the token to where a redirect points

    private GitLabSite() {
    }

    /** The address as entered, down to {@code https://host}; only https, since it carries a token. */
    static String normalize(String site) {
        String s = site == null || site.isBlank() ? "https://gitlab.com" : site.strip();
        if (!s.matches("(?i)[a-z]+://.*")) {
            s = "https://" + s;
        }
        URI u = URI.create(s);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) {
            throw new IllegalArgumentException("GitLab 地址要用 https，例如 https://gitlab.example.com");
        }
        return "https://" + u.getHost().toLowerCase(Locale.ROOT) + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /** Checks the token on the site and returns the site, which the authorization keeps. */
    static String verify(String site, String token) {
        String base = normalize(site);
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v4/user"))
                    .timeout(Duration.ofSeconds(15)).header("PRIVATE-TOKEN", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) {
                throw new IllegalArgumentException("GitLab 拒绝了这个令牌：确认它没过期，并带 api 和 write_repository 权限");
            }
            if (r.statusCode() != 200) {
                throw new IllegalArgumentException("连不上 " + base + " 的 GitLab 接口（HTTP " + r.statusCode() + "）：确认地址对不对");
            }
            JsonNode user = new ObjectMapper().readTree(r.body());
            if (!user.hasNonNull("username")) {
                throw new IllegalArgumentException(base + " 看起来不是 GitLab");
            }
            return base;
        } catch (IOException e) {
            throw new IllegalArgumentException("连不上 " + base + "：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("检查令牌被中断了，请再试一次");
        }
    }
}
