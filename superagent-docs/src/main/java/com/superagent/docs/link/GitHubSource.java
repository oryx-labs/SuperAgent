package com.superagent.docs.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.superagent.core.link.LinkContent;
import com.superagent.core.link.UnreadableLinkException;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Public GitHub: a file (its raw text), a repository (its README), an issue or pull request (with its comments), a
 * gist. Anything else on github.com is left to the generic page reader.
 */
final class GitHubSource implements LinkSource {

    private static final Map<String, String> API = Map.of("Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28");
    private static final int MAX_COMMENT_PAGES = 5;

    @Override
    public boolean handles(URI uri) {
        String host = host(uri);
        if (host.equals("raw.githubusercontent.com") || host.equals("gist.github.com")) {
            return true;
        }
        if (!host.equals("github.com") && !host.equals("www.github.com")) {
            return false;
        }
        String[] p = parts(uri);
        return p.length == 2 || (p.length >= 5 && (p[2].equals("blob") || p[2].equals("raw")))
                || (p.length >= 4 && (p[2].equals("issues") || p[2].equals("pull")) && p[3].matches("\\d+"));
    }

    @Override
    public LinkContent read(URI uri, String link, Fetcher fetcher) {
        String host = host(uri);
        String[] p = parts(uri);
        if (host.equals("raw.githubusercontent.com")) {
            return text(fetcher, uri, link, String.join("/", p));
        }
        if (host.equals("gist.github.com")) {
            return gist(fetcher, p[p.length - 1], link);
        }
        if (p.length == 2) {
            Fetcher.Page r = check(fetcher.get(URI.create("https://api.github.com/repos/" + p[0] + "/" + p[1] + "/readme"),
                    Map.of("Accept", "application/vnd.github.raw+json")), link);
            return new LinkContent(link, p[0] + "/" + p[1] + " README", r.text().strip(), LinkContent.PUBLIC, true);
        }
        if (p[2].equals("blob") || p[2].equals("raw")) {
            String rest = String.join("/", java.util.Arrays.copyOfRange(p, 3, p.length));
            return text(fetcher, URI.create("https://raw.githubusercontent.com/" + p[0] + "/" + p[1] + "/" + rest), link, rest);
        }
        return issue(fetcher, p[0], p[1], p[3], link);
    }

    private LinkContent text(Fetcher fetcher, URI raw, String link, String name) {
        Fetcher.Page r = check(fetcher.get(raw, Map.of()), link);
        return new LinkContent(link, name, r.text(), LinkContent.PUBLIC, true);
    }

    private LinkContent issue(Fetcher fetcher, String owner, String repo, String number, String link) {
        String base = "https://api.github.com/repos/" + owner + "/" + repo + "/issues/" + number;
        JsonNode issue = json(fetcher, base, link);
        StringBuilder sb = new StringBuilder();
        sb.append("@").append(issue.path("user").path("login").asText()).append("（").append(issue.path("state").asText())
                .append("）\n\n").append(issue.path("body").asText("")).append("\n");
        boolean complete = true;
        int total = issue.path("comments").asInt(0);
        int read = 0;
        for (int page = 1; read < total; page++) {
            if (page > MAX_COMMENT_PAGES) {
                complete = false;
                break;
            }
            JsonNode comments = json(fetcher, base + "/comments?per_page=100&page=" + page, link);
            if (comments.isEmpty()) {
                break;
            }
            for (JsonNode c : comments) {
                sb.append("\n---\n@").append(c.path("user").path("login").asText()).append("：\n").append(c.path("body").asText(""))
                        .append("\n");
                read++;
            }
        }
        return new LinkContent(link, owner + "/" + repo + " #" + number + " " + issue.path("title").asText(), sb.toString().strip(),
                LinkContent.PUBLIC, complete);
    }

    private LinkContent gist(Fetcher fetcher, String id, String link) {
        JsonNode gist = json(fetcher, "https://api.github.com/gists/" + id, link);
        StringBuilder sb = new StringBuilder();
        gist.path("files").forEach(f -> {
            String content = f.path("truncated").asBoolean(false)
                    ? check(fetcher.get(URI.create(f.path("raw_url").asText()), Map.of()), link).text() : f.path("content").asText("");
            sb.append("## ").append(f.path("filename").asText()).append("\n\n").append(content).append("\n\n");
        });
        String title = gist.path("description").asText("");
        return new LinkContent(link, title.isBlank() ? "Gist " + id : title, sb.toString().strip(), LinkContent.PUBLIC, true);
    }

    private JsonNode json(Fetcher fetcher, String url, String link) {
        return fetcher.json(check(fetcher.get(URI.create(url), API), link));
    }

    private static Fetcher.Page check(Fetcher.Page r, String link) {
        if (r.status() == 404) {
            throw new UnreadableLinkException(link, "GitHub 上找不到它，或者它不是公开的", "确认链接；私有仓库的内容请导出成文件发过来");
        }
        if (r.status() == 403 || r.status() == 429) {
            throw new UnreadableLinkException(link, "GitHub 匿名访问次数暂时用完了", "过一会儿再试");
        }
        if (!r.ok()) {
            throw new UnreadableLinkException(link, "GitHub 返回了 HTTP " + r.status(), "稍后再试");
        }
        return r;
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String[] parts(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("^/+|/+$", "");
        return path.isEmpty() ? new String[0] : path.split("/");
    }
}
