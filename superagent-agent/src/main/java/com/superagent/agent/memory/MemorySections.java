package com.superagent.agent.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An agent's memory as one Markdown document of {@code ## category} sections, with no I/O. Categories are open-ended;
 * the suggested ones come first. A memory written before categories (free text, or the old fixed headings) is sorted
 * into them here without losing a line: old headings map onto their new category, loose text goes to {@link #OTHER}.
 */
final class MemorySections {

    static final List<String> SUGGESTED = List.of("项目理解", "用户偏好", "工作约定", "踩过的坑");
    static final String OTHER = "其他";
    private static final Map<String, String> LEGACY = Map.of(
            "关于用户", "用户偏好",
            "目标与项目", "项目理解",
            "决定与约定", "工作约定");

    /** Category → its text (stripped, never blank), in document order. */
    static LinkedHashMap<String, String> parse(String memory) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (memory == null || memory.isBlank()) {
            return out;
        }
        String current = OTHER;
        StringBuilder body = new StringBuilder();
        for (String line : memory.strip().split("\n", -1)) {
            if (line.startsWith("## ")) {
                put(out, current, body.toString());
                current = category(line.substring(3));
                body.setLength(0);
            } else if (line.startsWith("# ") && body.isEmpty() && out.isEmpty() && current.equals(OTHER)) {
                // the document's title line: rebuilt by render
            } else {
                body.append(line).append('\n');
            }
        }
        put(out, current, body.toString());
        return out;
    }

    /** The document: a title line, then each category; null when there is nothing in it. */
    static String render(String agentName, Map<String, String> sections) {
        List<String> parts = new ArrayList<>();
        sections.forEach((cat, text) -> {
            if (text != null && !text.isBlank()) {
                parts.add("## " + cat + "\n" + text.strip());
            }
        });
        return parts.isEmpty() ? null : "# " + agentName + " 的记忆\n\n" + String.join("\n\n", parts) + "\n";
    }

    /** The memory with {@code content} added to the end of {@code category}, or replacing it ({@code replace}). */
    static LinkedHashMap<String, String> write(String memory, String category, String content, boolean replace) {
        LinkedHashMap<String, String> sections = parse(memory);
        String cat = category(category);
        String text = content == null ? "" : content.strip();
        if (replace || !sections.containsKey(cat)) {
            if (text.isEmpty()) {
                sections.remove(cat);
            } else {
                sections.put(cat, text);
            }
        } else if (!text.isEmpty()) {
            sections.put(cat, sections.get(cat) + "\n" + text);
        }
        return sections;
    }

    /**
     * What distilling leaves: the model's categories, except that those the agent wrote ({@code agentOwned}) keep their
     * text and only gain the model's new lines; a category the model left out stays as it was, and one it emptied goes
     * (unless the agent owns it).
     */
    static LinkedHashMap<String, String> merge(String current, String distilled, Collection<String> agentOwned) {
        LinkedHashMap<String, String> now = parse(current);
        LinkedHashMap<String, String> model = parseKeepingEmpty(distilled);
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        Set<String> order = new LinkedHashSet<>(now.keySet());
        order.addAll(model.keySet());
        for (String cat : order) {
            String mine = now.get(cat);
            String theirs = model.get(cat);
            if (theirs == null) {
                put(out, cat, mine);
            } else if (agentOwned.contains(cat) && mine != null) {
                put(out, cat, addNewLines(mine, theirs));
            } else {
                put(out, cat, theirs);
            }
        }
        return out;
    }

    static List<String> owned(String agentSections) {
        if (agentSections == null || agentSections.isBlank()) {
            return List.of();
        }
        return agentSections.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    static String withOwned(String agentSections, String category) {
        Set<String> all = new LinkedHashSet<>(owned(agentSections));
        all.add(category(category));
        return String.join("\n", all);
    }

    static String category(String raw) {
        String c = raw == null ? "" : raw.strip().replaceAll("^#+\\s*", "");
        if (c.isEmpty()) {
            return OTHER;
        }
        return LEGACY.getOrDefault(c, c);
    }

    private static String addNewLines(String mine, String theirs) {
        Set<String> seen = new LinkedHashSet<>(mine.lines().map(String::strip).toList());
        StringBuilder out = new StringBuilder(mine.strip());
        theirs.lines().filter(l -> !l.isBlank() && !seen.contains(l.strip())).forEach(l -> out.append('\n').append(l));
        return out.toString();
    }

    /** Like {@link #parse}, but a heading with nothing under it is kept (as "") to mean "drop this category". */
    private static LinkedHashMap<String, String> parseKeepingEmpty(String memory) {
        LinkedHashMap<String, String> out = parse(memory);
        if (memory != null) {
            memory.lines().filter(l -> l.startsWith("## ")).map(l -> category(l.substring(3)))
                    .forEach(cat -> out.putIfAbsent(cat, ""));
        }
        return out;
    }

    private static void put(Map<String, String> sections, String cat, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String t = text.strip();
        sections.merge(cat, t, (a, b) -> a + "\n" + b); // two old headings landing in one category
    }

    private MemorySections() {
    }
}
