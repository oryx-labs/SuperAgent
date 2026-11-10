package com.superagent.core.link;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The web links written in a piece of text (a task's brief), in order, without duplicates. */
public final class LinkUrls {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`，。；、（）【】《》「」]+");
    // Punctuation that ends a sentence or closes Markdown around a link, not part of it.
    private static final String TRAILING = ".,;:!?)]}*_~>";

    private LinkUrls() {
    }

    public static List<String> in(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = m.group();
            while (!url.isEmpty() && TRAILING.indexOf(url.charAt(url.length() - 1)) >= 0) {
                if (url.endsWith(")") && url.indexOf('(') >= 0 && count(url, '(') >= count(url, ')')) {
                    break; // a balanced "(…)" inside the link (Wikipedia-style) is part of it
                }
                url = url.substring(0, url.length() - 1);
            }
            if (url.length() > "https://".length()) {
                out.add(url);
            }
        }
        return new ArrayList<>(out);
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(x -> x == c).count();
    }
}
