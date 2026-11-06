package com.superagent.core.org;

import java.util.ArrayList;
import java.util.List;

/** Team paths: validation, normalisation, and the "a team sees the teams above it" rule. */
public final class TeamPaths {

    private static final int MAX_SEGMENT = 64;
    private static final int MAX_DEPTH = 8;

    /**
     * Trims each segment and joins them with "/". Rejects empty segments, dot-names, control characters
     * and "›", so a path is always safe to store and show.
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Team path is empty");
        }
        List<String> segments = new ArrayList<>();
        for (String raw : path.split("/")) {
            String s = raw.strip();
            if (s.isEmpty()) {
                continue; // tolerate "a//b", leading or trailing "/"
            }
            if (s.length() > MAX_SEGMENT || s.startsWith(".") || s.contains("›")
                    || s.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c == '\\')) {
                throw new IllegalArgumentException("Invalid team name: " + s);
            }
            segments.add(s);
        }
        if (segments.isEmpty() || segments.size() > MAX_DEPTH) {
            throw new IllegalArgumentException("Invalid team path: " + path);
        }
        return String.join("/", segments);
    }

    /** The team itself and every team above it: {@code a/b/c} → {@code a/b/c, a/b, a}. */
    public static List<String> withAncestors(String team) {
        List<String> out = new ArrayList<>();
        String t = team;
        while (true) {
            out.add(t);
            int slash = t.lastIndexOf('/');
            if (slash < 0) {
                return out;
            }
            t = t.substring(0, slash);
        }
    }

    /** True when {@code team} is {@code root} or lies under it. */
    public static boolean within(String team, String root) {
        return team.equals(root) || team.startsWith(root + "/");
    }

    private TeamPaths() {
    }
}
