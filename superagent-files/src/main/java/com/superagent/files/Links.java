package com.superagent.files;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finding web addresses in what people type, and naming them for a file list. */
final class Links {

    static final int MAX_PER_MESSAGE = 10;
    /** Up to the first space, quote, angle bracket or full-width punctuation; trailing punctuation is trimmed below. */
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`，。；、！？（）【】《》「」]+",
            Pattern.CASE_INSENSITIVE);
    private static final String TRAILING = ".,;:!?)]}'\"";

    private Links() {
    }

    static List<String> find(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = URL.matcher(text);
        while (m.find() && out.size() < MAX_PER_MESSAGE) {
            String url = m.group();
            while (!url.isEmpty() && TRAILING.indexOf(url.charAt(url.length() - 1)) >= 0) {
                url = url.substring(0, url.length() - 1);
            }
            if (url.length() <= 2000 && valid(url)) {
                out.add(url);
            }
        }
        return new ArrayList<>(out);
    }

    /** "github.com/org/repo" — the host plus the start of the path, short enough for a list. */
    static String name(String url) {
        URI uri = URI.create(url);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String name = uri.getHost() + (path.equals("/") ? "" : path);
        try {
            name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // keep it encoded
        }
        return name.length() <= 120 ? name : name.substring(0, 117) + "…";
    }

    private static boolean valid(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
