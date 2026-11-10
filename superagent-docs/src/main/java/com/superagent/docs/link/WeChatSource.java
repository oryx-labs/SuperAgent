package com.superagent.docs.link;

import com.superagent.core.link.LinkContent;
import com.superagent.core.link.UnreadableLinkException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.Map;

/** WeChat official-account articles: the article body is in the page itself (#js_content), title in #activity-name. */
final class WeChatSource implements LinkSource {

    @Override
    public boolean handles(URI uri) {
        return "mp.weixin.qq.com".equalsIgnoreCase(uri.getHost()) && uri.getRawPath() != null && uri.getRawPath().startsWith("/s");
    }

    @Override
    public LinkContent read(URI uri, String link, Fetcher fetcher) {
        Fetcher.Page p = fetcher.get(uri, Map.of("Referer", "https://mp.weixin.qq.com/"));
        Document doc = Jsoup.parse(p.text(), uri.toString());
        Element body = doc.selectFirst("#js_content");
        if (!p.ok() || body == null || p.uri().toString().contains("captcha")) {
            throw new UnreadableLinkException(link, "公众号要求人机验证，或文章已被删除", "稍后再试，或把文章内容复制过来");
        }
        Element name = doc.selectFirst("#activity-name");
        String title = name != null && !name.text().isBlank() ? name.text() : HtmlText.title(doc);
        return new LinkContent(link, title, HtmlText.render(body), LinkContent.PUBLIC, true);
    }
}
