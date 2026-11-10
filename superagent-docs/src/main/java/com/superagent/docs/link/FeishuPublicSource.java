package com.superagent.docs.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.link.LinkContent;
import com.superagent.core.link.UnreadableLinkException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feishu / Lark documents shared as "anyone on the internet with the link can read", read the way their web page
 * loads them: the block map from {@code /space/api/docx/pages/client_vars}, page after page until it has no more. A
 * wiki link is first resolved to the document it holds. These are the web page's own endpoints, not the Open API:
 * if they change, the read fails cleanly (and an authorized user is still read through the Open API).
 */
final class FeishuPublicSource implements LinkSource {

    private static final Pattern DOCX = Pattern.compile("/docx/([A-Za-z0-9]+)");
    private static final Pattern WIKI = Pattern.compile("/wiki/([A-Za-z0-9]+)");
    private static final int PAGE_SIZE = 239;
    private static final int MAX_PAGES = 80;
    private static final ObjectMapper JSON = new ObjectMapper();

    static boolean isFeishuDoc(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean feishu = host.equals("feishu.cn") || host.endsWith(".feishu.cn") || host.endsWith(".larksuite.com")
                || host.equals("larksuite.com") || host.endsWith(".larkoffice.com") || host.endsWith(".feishu.net");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return feishu && (path.contains("/docx/") || path.contains("/wiki/") || path.contains("/docs/")
                || path.contains("/sheets/") || path.contains("/base/"));
    }

    @Override
    public boolean handles(URI uri) {
        return isFeishuDoc(uri);
    }

    @Override
    public LinkContent read(URI uri, String link, Fetcher fetcher) {
        String path = uri.getRawPath();
        if (!path.contains("/docx/") && !path.contains("/wiki/")) {
            throw new UnreadableLinkException(link, "这是飞书" + (path.contains("/sheets/") ? "表格" : path.contains("/base/") ? "多维表格" : "旧版文档")
                    + "，目前只能读新版文档和知识库页面", "把内容导出成文件发过来");
        }
        String base = uri.getScheme() + "://" + uri.getHost();
        Map<String, String> referer = Map.of("Referer", uri.toString());
        Fetcher.Page page = fetcher.get(uri, Map.of()); // the anonymous session cookies the endpoints below expect
        if (isLogin(page.uri())) {
            throw notPublic(link);
        }
        String id = documentId(base, path, referer, fetcher, link);
        ObjectNode blocks = JSON.createObjectNode();
        Deque<String> cursors = new ArrayDeque<>();
        cursors.add("");
        Set<String> seen = new HashSet<>();
        int pages = 0;
        boolean more = false;
        while (!cursors.isEmpty()) {
            String cursor = cursors.poll();
            if (!seen.add(cursor)) {
                continue;
            }
            if (++pages > MAX_PAGES) {
                more = true;
                break;
            }
            JsonNode res = fetcher.json(URI.create(base + "/space/api/docx/pages/client_vars?id=" + id + "&mode=7&limit=" + PAGE_SIZE
                    + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8))), referer);
            if (res.path("code").asInt(-1) != 0) {
                throw notPublic(link);
            }
            JsonNode data = res.path("data");
            data.path("block_map").fields().forEachRemaining(e -> blocks.set(e.getKey(), e.getValue()));
            if (data.path("has_more").asBoolean(false)) {
                data.path("next_cursors").forEach(c -> cursors.add(c.asText()));
            }
        }
        FeishuBlocks.Rendered r = FeishuBlocks.render(id, blocks);
        if (r.text().isBlank() && r.title().isBlank()) {
            throw notPublic(link);
        }
        return new LinkContent(link, r.title().isBlank() ? "飞书文档" : r.title(), r.text(), LinkContent.PUBLIC,
                r.complete() && !more);
    }

    private String documentId(String base, String path, Map<String, String> referer, Fetcher fetcher, String link) {
        Matcher d = DOCX.matcher(path);
        if (d.find()) {
            return d.group(1);
        }
        Matcher w = WIKI.matcher(path);
        if (!w.find()) {
            throw notPublic(link);
        }
        JsonNode res = fetcher.json(URI.create(base + "/space/api/wiki/v2/tree/get_node/?wiki_token=" + w.group(1)
                + "&expand_shortcut=true"), referer);
        String token = find(res.path("data"), "obj_token");
        String type = find(res.path("data"), "obj_type");
        if (res.path("code").asInt(-1) != 0 || token == null) {
            throw notPublic(link);
        }
        if (type != null && !type.equals("docx") && !type.equals("22")) {
            throw new UnreadableLinkException(link, "这个知识库页面不是新版文档，目前只能读新版文档", "把内容导出成文件发过来");
        }
        return token;
    }

    /** The first value of {@code field} anywhere under {@code node} (the wiki node sits at different depths). */
    private static String find(JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isContainerNode()) {
            return node.get(field).asText();
        }
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                String v = find(child, field);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static boolean isLogin(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        return host.startsWith("accounts.") || host.startsWith("passport.") || uri.getPath().contains("/login");
    }

    static UnreadableLinkException notPublic(String link) {
        return new UnreadableLinkException(link, "这篇飞书文档不是公开的（没有开「互联网上获得链接的人可阅读」）",
                "在 SuperAgent 的「连接」里授权飞书文档后可以按你本人的权限读取，或把文档的分享设置改为互联网上获得链接的人可阅读");
    }
}
