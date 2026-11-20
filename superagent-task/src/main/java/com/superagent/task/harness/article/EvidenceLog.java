package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Evidence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An investigation's evidence log as the author writes it: entries {@code ### E3 | 代码 | path:line}, {@code | 命令 | cmd}
 * (with 输出), {@code | 数字 | figure} (with 环境) and {@code | 文档 | title | version | link}. Parsed and checked by the
 * platform: a log with nothing in it, or an entry that points at nothing, never reaches the outline.
 */
final class EvidenceLog {

    static final List<String> TYPES = List.of("代码", "命令", "数字", "文档");
    private static final Pattern HEAD = Pattern.compile("^#{2,4}\\s+(E\\d+)\\s*[|｜]\\s*(代码|命令|数字|文档)\\s*[|｜]\\s*(.+?)\\s*$");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern VERSION = Pattern.compile("版本|[Vv]ersion|\\bv?\\d+\\.\\d+");

    private EvidenceLog() {
    }

    static List<Evidence> parse(String text) {
        List<Evidence> out = new ArrayList<>();
        String id = null, type = null, key = null;
        StringBuilder body = new StringBuilder();
        for (String line : (text == null ? "" : text).lines().toList()) {
            Matcher m = HEAD.matcher(line.strip());
            boolean other = line.stripLeading().startsWith("## ") && !m.matches();
            if (m.matches() || other) {
                if (id != null) {
                    out.add(new Evidence(id, type, key, body.toString().strip()));
                }
                id = m.matches() ? m.group(1) : null;
                type = m.matches() ? m.group(2) : null;
                key = m.matches() ? m.group(3) : null;
                body.setLength(0);
            } else if (id != null) {
                body.append(line).append('\n');
            }
        }
        if (id != null) {
            out.add(new Evidence(id, type, key, body.toString().strip()));
        }
        return out;
    }

    /** Why the log isn't usable yet (empty = fine); {@code repo} is where code references are looked up (null: not checked). */
    static List<String> problems(List<Evidence> log, Path repo) {
        List<String> out = new ArrayList<>();
        if (log.isEmpty()) {
            out.add("证据日志是空的：没有找到「### E1 | 代码 | path:行号」这样的条目");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Evidence e : log) {
            if (!seen.add(e.id())) {
                out.add(e.id() + " 编号重复");
            }
            switch (e.type()) {
                case "代码" -> {
                    List<TechChecks.Ref> refs = TechChecks.refs(e.key());
                    if (refs.isEmpty()) {
                        out.add(e.id() + "（代码）要点要写成 path:行号 或 path:起-止，现在是「" + e.key() + "」");
                    } else if (repo != null) {
                        refs.forEach(r -> TechChecks.resolve(repo, r).ifPresent(err -> out.add(e.id() + "：" + err)));
                    }
                    if (e.body().isBlank()) {
                        out.add(e.id() + "（代码）没有写看到了什么");
                    }
                }
                case "命令" -> {
                    if (!e.body().contains("输出") || e.body().replaceFirst("(?s).*?输出[：:]?", "").isBlank()) {
                        out.add(e.id() + "（命令）没有记录输出：要有一行「输出：」和命令的实际输出");
                    }
                }
                case "数字" -> {
                    if (!e.body().contains("环境")) {
                        out.add(e.id() + "（数字）没有写环境：要有一行「环境：版本、机器、数据量」");
                    }
                }
                default -> {
                    if (!URL.matcher(e.key()).find() || !VERSION.matcher(e.key()).find()) {
                        out.add(e.id() + "（文档）要点要写成「标题 | 版本 | 链接」，缺版本或链接：" + e.key());
                    }
                }
            }
        }
        return out;
    }

    /** All the log's text, for tracing numbers and for the verifier. */
    static String pool(List<Evidence> log) {
        StringBuilder sb = new StringBuilder();
        for (Evidence e : log) {
            sb.append(e.id()).append(' ').append(e.key()).append('\n').append(e.body()).append('\n');
        }
        return sb.toString();
    }

    /** The log as the reviewers and the author read it. */
    static String render(List<Evidence> log) {
        StringBuilder sb = new StringBuilder();
        for (Evidence e : log) {
            sb.append("### ").append(e.id()).append(" | ").append(e.type()).append(" | ").append(e.key()).append('\n')
                    .append(e.body()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
