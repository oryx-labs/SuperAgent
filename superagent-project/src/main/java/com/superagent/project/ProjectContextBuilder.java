package com.superagent.project;

import com.superagent.core.project.ProjectBrief;
import com.superagent.core.project.ProjectMemory;
import com.superagent.core.project.ProjectRepo;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes a project's shared context for an agent's prompt: the brief, an index of its files and repositories (one line
 * each), and its memory. Within {@link #MAX} characters it's all there; beyond, the index and memory go down to titles
 * (as many as fit) and the agent reads the rest with {@code project_context_read}.
 */
final class ProjectContextBuilder {

    static final int MAX = 6000;
    private static final int BRIEF_SHARE = MAX / 2;

    /** A file in the project's folders: its name, id and a one-line note (folder, tags). */
    record FileEntry(String id, String name, String note) {
    }

    private ProjectContextBuilder() {
    }

    static String build(String projectName, ProjectBrief brief, List<FileEntry> files, List<ProjectRepo> repos,
                        List<ProjectMemory> memories, int max) {
        String head = "## 项目共同上下文（项目「" + projectName + "」）\n"
                + "你在这个项目里，项目里所有 Agent 共享下面这份上下文。用 project_context_read 读完整内容，"
                + "有新的决策、约定或踩过的坑，用 project_memory_write 记进项目记忆。\n";
        String full = head + briefSection(brief, Integer.MAX_VALUE) + section("资料索引", indexLines(files, repos, true))
                + section("项目记忆", memoryLines(memories, true));
        if (full.length() <= max) {
            return full.strip();
        }
        StringBuilder sb = new StringBuilder(head)
                .append("（内容超过 ").append(max).append(" 字，资料索引和项目记忆只列标题，正文用 project_context_read 读。）\n")
                .append(briefSection(brief, Math.min(BRIEF_SHARE, max / 2)));
        List<String> titles = new ArrayList<>();
        indexLines(files, repos, false).forEach(l -> titles.add("I" + l));
        memoryLines(memories, false).forEach(l -> titles.add("M" + l));
        boolean indexOpen = false;
        boolean memoryOpen = false;
        int shown = 0;
        for (String t : titles) {
            String heading = t.startsWith("I") ? (indexOpen ? "" : "\n### 资料索引\n") : (memoryOpen ? "" : "\n### 项目记忆\n");
            String line = heading + t.substring(1) + "\n";
            if (sb.length() + line.length() + 40 > max) {
                break;
            }
            sb.append(line);
            indexOpen |= t.startsWith("I");
            memoryOpen |= t.startsWith("M");
            shown++;
        }
        if (shown < titles.size()) {
            sb.append("…还有 ").append(titles.size() - shown).append(" 条，用 project_context_read 查看\n");
        }
        String out = sb.toString().strip();
        return out.length() <= max ? out : out.substring(0, max);
    }

    private static String briefSection(ProjectBrief b, int cap) {
        if (b == null || b.empty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        field(sb, "目标", b.goal());
        field(sb, "背景", b.background());
        field(sb, "规范", b.norms());
        field(sb, "术语", b.glossary());
        String text = sb.toString().strip();
        if (text.length() > cap) {
            text = text.substring(0, Math.max(0, cap - 40)) + "…（完整说明用 project_context_read 读）";
        }
        return "\n### 项目说明\n" + text + "\n";
    }

    private static void field(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append("：").append(value.strip()).append("\n");
        }
    }

    private static List<String> indexLines(List<FileEntry> files, List<ProjectRepo> repos, boolean full) {
        List<String> out = new ArrayList<>();
        for (ProjectRepo r : repos) {
            String line = "- 仓库「" + r.name() + "」" + r.location();
            if (full) {
                line += "（默认分支 " + (r.defaultBranch() == null ? "仓库默认" : r.defaultBranch()) + "，交付 " + r.delivery() + "）"
                        + (r.purpose() == null ? "" : "：" + r.purpose());
            }
            out.add(line);
        }
        for (FileEntry f : files) {
            out.add("- 文件「" + f.name() + "」（id: " + f.id() + "）" + (full && f.note() != null && !f.note().isBlank() ? "：" + f.note() : ""));
        }
        return out;
    }

    private static List<String> memoryLines(List<ProjectMemory> memories, boolean full) {
        return memories.stream().map(m -> "- " + m.title() + "（id: " + m.id() + "）"
                + (full && m.content() != null && !m.content().isBlank() ? "：" + m.content().strip() : "")).toList();
    }

    private static String section(String title, List<String> lines) {
        return lines.isEmpty() ? "" : "\n### " + title + "\n" + String.join("\n", lines) + "\n";
    }
}
