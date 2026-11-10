package com.superagent.docs.link;

import com.superagent.core.link.LinkContent;
import com.superagent.core.link.UnreadableLinkException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Any other link: a web page's main text, a PDF's text, or a plain-text file as it is. */
final class GenericSource implements LinkSource {

    /** Less main text than this from a whole page means its content is drawn by scripts, not sent with the page. */
    private static final int MIN_PAGE_TEXT = 80;

    @Override
    public boolean handles(URI uri) {
        return true;
    }

    @Override
    public LinkContent read(URI uri, String link, Fetcher fetcher) {
        Fetcher.Page p = fetcher.get(uri, Map.of());
        if (p.status() == 401 || p.status() == 403 || isLogin(p.uri())) {
            throw new UnreadableLinkException(link, "这个页面要登录才能看", "公司内部资料请导出成文件发过来，或授权对应的连接");
        }
        if (p.status() == 404 || p.status() == 410) {
            throw new UnreadableLinkException(link, "页面不存在（HTTP " + p.status() + "）", "检查一下链接");
        }
        if (!p.ok()) {
            throw new UnreadableLinkException(link, "网站返回了 HTTP " + p.status(), "稍后再试");
        }
        if (p.is("pdf") || startsWith(p.body(), "%PDF")) {
            return pdf(p, link);
        }
        if (p.is("html") || p.contentType().isBlank() && new String(p.body(), 0, Math.min(512, p.body().length)).toLowerCase(Locale.ROOT).contains("<html")) {
            Document doc = Jsoup.parse(p.text(), p.uri().toString());
            String title = HtmlText.title(doc);
            String text = HtmlText.main(doc);
            if (text.length() < MIN_PAGE_TEXT) {
                throw new UnreadableLinkException(link, "这个页面的内容要在浏览器里由脚本加载，暂时读不到",
                        "把内容复制过来，或导出成文件发过来");
            }
            return new LinkContent(link, title.isBlank() ? p.uri().getHost() : title, text, LinkContent.PUBLIC, true);
        }
        if (p.contentType().startsWith("text/") || p.is("json") || p.is("xml") || p.is("markdown") || p.is("csv")) {
            return new LinkContent(link, name(p.uri()), p.text(), LinkContent.PUBLIC, true);
        }
        throw new UnreadableLinkException(link, "这不是网页、PDF 或文本（" + p.contentType() + "），暂时读不了", "下载后作为文件发过来");
    }

    private static LinkContent pdf(Fetcher.Page p, String link) {
        try (PDDocument doc = Loader.loadPDF(p.body())) {
            String text = new PDFTextStripper().getText(doc).strip();
            if (text.isBlank()) {
                throw new UnreadableLinkException(link, "这个 PDF 里没有可提取的文字（可能是扫描件）", "发一份文字版过来");
            }
            String title = doc.getDocumentInformation() == null ? null : doc.getDocumentInformation().getTitle();
            return new LinkContent(link, title == null || title.isBlank() ? name(p.uri()) : title, text, LinkContent.PUBLIC, true);
        } catch (IOException e) {
            throw new UnreadableLinkException(link, "这个 PDF 打不开：" + e.getMessage(), "下载后作为文件发过来");
        }
    }

    private static boolean isLogin(URI uri) {
        String s = (uri.getHost() + uri.getPath()).toLowerCase(Locale.ROOT);
        return s.contains("/login") || s.contains("/signin") || s.contains("/passport") || s.startsWith("passport.")
                || s.startsWith("login.") || s.startsWith("accounts.");
    }

    private static boolean startsWith(byte[] body, String magic) {
        if (body.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (body[i] != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String name(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? uri.getHost() : last;
    }
}
