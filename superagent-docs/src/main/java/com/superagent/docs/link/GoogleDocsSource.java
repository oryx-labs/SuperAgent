package com.superagent.docs.link;

import com.superagent.core.link.LinkContent;
import com.superagent.core.link.UnreadableLinkException;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Google Docs / Sheets / Slides shared as "anyone with the link": their export URLs give the whole text at once. */
final class GoogleDocsSource implements LinkSource {

    private static final Pattern DOC = Pattern.compile("/(document|spreadsheets|presentation)/d/([A-Za-z0-9_-]+)");

    @Override
    public boolean handles(URI uri) {
        return "docs.google.com".equalsIgnoreCase(uri.getHost()) && DOC.matcher(uri.getRawPath()).find();
    }

    @Override
    public LinkContent read(URI uri, String link, Fetcher fetcher) {
        Matcher m = DOC.matcher(uri.getRawPath());
        m.find();
        String kind = m.group(1);
        String id = m.group(2);
        String export = switch (kind) {
            case "document" -> "/document/d/" + id + "/export?format=txt";
            case "spreadsheets" -> "/spreadsheets/d/" + id + "/export?format=csv";
            default -> "/presentation/d/" + id + "/export/txt";
        };
        Fetcher.Page p = fetcher.get(URI.create("https://docs.google.com" + export), Map.of());
        if (!p.ok() || p.is("text/html") || p.uri().getHost().startsWith("accounts.")) {
            throw new UnreadableLinkException(link, "这份 Google 文档不是公开的，或主人关闭了下载",
                    "把共享设置改为「知道链接的任何人都可查看」，或导出成文件发过来");
        }
        String title = switch (kind) {
            case "document" -> "Google 文档";
            case "spreadsheets" -> "Google 表格（第一个工作表）";
            default -> "Google 幻灯片";
        };
        return new LinkContent(link, title, p.text().strip(), LinkContent.PUBLIC, true);
    }
}
