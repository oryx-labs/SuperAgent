package com.superagent.docs.link;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/** A web page's main text as light Markdown: headings, paragraphs, lists, tables and code, without the page chrome. */
final class HtmlText {

    private static final String CHROME = "script,style,noscript,template,svg,canvas,iframe,form,nav,footer,header,aside,"
            + "button,input,select,textarea,[aria-hidden=true],[hidden]";
    private static final String MAIN = "article,main,[role=main],#content,#main,.content,.article,.post,.markdown-body";

    private HtmlText() {
    }

    static String title(Document doc) {
        Element og = doc.selectFirst("meta[property=og:title]");
        if (og != null && !og.attr("content").isBlank()) {
            return og.attr("content").strip();
        }
        if (!doc.title().isBlank()) {
            return doc.title().strip();
        }
        Element h1 = doc.selectFirst("h1");
        return h1 == null ? "" : h1.text();
    }

    /** The page's main content: the article / main element when there is a substantial one, else the whole body. */
    static String main(Document doc) {
        doc.select(CHROME).remove();
        Element body = doc.body();
        Element best = null;
        for (Element e : doc.select(MAIN)) {
            if (best == null || e.text().length() > best.text().length()) {
                best = e;
            }
        }
        Element root = best != null && best.text().length() >= Math.min(200, body.text().length() / 3) ? best : body;
        return render(root);
    }

    static String render(Element root) {
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        return sb.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static void walk(Node node, StringBuilder sb, int depth) {
        if (node instanceof TextNode t) {
            String text = t.text();
            if (!text.isBlank() || (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n')) {
                sb.append(text);
            }
            return;
        }
        if (!(node instanceof Element e)) {
            return;
        }
        switch (e.normalName()) {
            case "br" -> sb.append('\n');
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                block(sb);
                sb.append("#".repeat(e.normalName().charAt(1) - '0')).append(' ').append(e.text()).append("\n\n");
            }
            case "li" -> {
                line(sb);
                sb.append("  ".repeat(Math.max(0, depth - 1))).append("- ");
                e.childNodes().forEach(c -> walk(c, sb, depth));
                line(sb);
            }
            case "ul", "ol" -> {
                block(sb);
                e.childNodes().forEach(c -> walk(c, sb, depth + 1));
                block(sb);
            }
            case "pre" -> {
                block(sb);
                sb.append("```\n").append(e.wholeText().strip()).append("\n```\n\n");
            }
            case "table" -> {
                block(sb);
                boolean first = true;
                for (Element tr : e.select("tr")) {
                    var cells = tr.select("> th, > td");
                    if (cells.isEmpty()) {
                        continue;
                    }
                    sb.append("| ");
                    cells.forEach(c -> sb.append(c.text().replace("|", "\\|")).append(" | "));
                    sb.setLength(sb.length() - 1);
                    sb.append('\n');
                    if (first) {
                        sb.append("|").append(" --- |".repeat(cells.size())).append('\n');
                        first = false;
                    }
                }
                sb.append('\n');
            }
            case "img" -> {
                String alt = e.attr("alt").strip();
                if (!alt.isEmpty()) {
                    sb.append("[图片：").append(alt).append(']');
                }
            }
            case "p", "div", "section", "article", "main", "blockquote", "figure", "figcaption", "dl", "dt", "dd" -> {
                block(sb);
                e.childNodes().forEach(c -> walk(c, sb, depth));
                block(sb);
            }
            default -> e.childNodes().forEach(c -> walk(c, sb, depth));
        }
    }

    private static void line(StringBuilder sb) {
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void block(StringBuilder sb) {
        line(sb);
        if (sb.length() >= 2 && sb.charAt(sb.length() - 2) != '\n') {
            sb.append('\n');
        }
    }
}
