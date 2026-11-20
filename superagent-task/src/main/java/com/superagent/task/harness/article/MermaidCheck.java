package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Issue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.superagent.task.harness.article.ArticleChecks.FAIL;
import static com.superagent.task.harness.article.ArticleChecks.PASS;
import static com.superagent.task.harness.article.ArticleChecks.WARN;

/**
 * Diagrams in a draft: each {@code mermaid} block must be syntactically sound (a light structural check in Java, not
 * Mermaid's own parser), sit next to a numbered caption ({@code 图 1　…}, numbered 1, 2, … in order), be referred to
 * in the text ({@code 见图 1}), and have at most {@value #MAX_NODES} nodes. Labels that appear nowhere else in the
 * document only warn: a diagram's names should be the text's names.
 */
final class MermaidCheck {

    static final int MAX_NODES = 15;
    private static final Pattern BLOCK = Pattern.compile("```mermaid[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern CAPTION = Pattern.compile("^[\\s*_>\\-]*(?:图|Figure|Fig\\.?)\\s*(\\d+)(?!\\d).*");
    private static final Pattern TYPE = Pattern.compile("^(graph|flowchart)(?:\\s+(TB|TD|BT|RL|LR))?\\s*;?$|^(sequenceDiagram|erDiagram|classDiagram|"
            + "stateDiagram(?:-v2)?|gantt|pie(?:\\s+.*)?|journey|mindmap|timeline|gitGraph.*|quadrantChart|requirementDiagram)$");
    private static final Pattern EDGE = Pattern.compile("(<?-->|<?---|-\\.+->?|<?==+>?|--[ox]|~~~|<?-{2,}>?)");
    private static final Pattern FLOW_NODE = Pattern.compile("([A-Za-z_][\\w-]*)\\s*(?:\\[\\[?\"?([^\\]\"]*)\"?\\]?\\]|\\(\\(?\"?([^)\"]*)\"?\\)?\\)|\\{\"?([^}\"]*)\"?\\}|>\\s*\"?([^\\]\"]*)\"?\\])?");
    private static final Pattern FLOW_STMT = Pattern.compile("^(subgraph\\b.*|end|direction\\s+(TB|TD|BT|RL|LR)|classDef\\b.*|class\\b.*|style\\b.*|linkStyle\\b.*|click\\b.*)$");
    private static final Pattern SEQ_OPEN = Pattern.compile("^(alt|loop|opt|par|critical|break|rect|box)\\b.*");
    private static final Pattern SEQ_MID = Pattern.compile("^(else|and|option)\\b.*");
    private static final Pattern SEQ_MSG = Pattern.compile("^([^\\s:\\-]+)\\s*(-->>|->>|--x|-x|-->|->|--\\)|-\\))\\s*[+-]?([^\\s:]+)\\s*:\\s*.+$");
    private static final Pattern SEQ_PART = Pattern.compile("^(participant|actor)\\s+(\\S+)(?:\\s+as\\s+(.+))?$");
    private static final Pattern SEQ_OTHER = Pattern.compile("^(autonumber.*|title\\b.*|activate\\s+\\S+|deactivate\\s+\\S+|Note\\s+(over|left of|right of)\\s+[^:]+:.+|%%.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ER_REL = Pattern.compile("^(\\S+)\\s+[|}o]{1,2}(?:--|\\.\\.)[|{o]{1,2}\\s+(\\S+)\\s*:\\s*.+$");
    private static final Pattern ER_OPEN = Pattern.compile("^(\\S+)\\s*\\{$");
    private static final Pattern STATE_EDGE = Pattern.compile("^(\\[\\*\\]|[\\w.-]+)\\s*-->\\s*(\\[\\*\\]|[\\w.-]+)(?:\\s*:\\s*.+)?$");

    private MermaidCheck() {
    }

    /** One diagram: what is wrong with it, and its labels (the names a reader sees). */
    record Diagram(int index, String source, int captionNumber, List<String> problems, Set<String> labels, int nodes) {
    }

    static List<Issue> check(String draft) {
        String text = draft == null ? "" : draft;
        Matcher m = BLOCK.matcher(text);
        List<Issue> out = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            sources.add(m.group(1));
        }
        if (spans.isEmpty()) {
            return List.of(new Issue("图", PASS, null, "没有 Mermaid 图"));
        }
        String outside = BLOCK.matcher(text).replaceAll(" ");
        List<String> lines = text.lines().toList();
        for (int i = 0; i < spans.size(); i++) {
            String name = "图 " + (i + 1);
            Diagram d = parse(i + 1, sources.get(i));
            d.problems().forEach(p -> out.add(new Issue("图", FAIL, name, "语法：" + p)));
            if (d.nodes() > MAX_NODES) {
                out.add(new Issue("图", FAIL, name, "有 " + d.nodes() + " 个节点，最多 " + MAX_NODES + " 个：拆成两张图"));
            }
            int number = captionNumber(text, lines, spans.get(i));
            if (number < 0) {
                out.add(new Issue("图", FAIL, name, "代码块旁边没有编号标题，要写成「图 " + (i + 1) + "　标题」"));
            } else if (number != i + 1) {
                out.add(new Issue("图", FAIL, name, "编号应该是 " + (i + 1) + "，写的是 " + number));
            }
            if (!referenced(outside, i + 1)) {
                out.add(new Issue("图", FAIL, name, "正文里没有引用它（写「见图 " + (i + 1) + "」）"));
            }
            List<String> named = d.labels().stream().filter(l -> l.length() >= 2).toList();
            List<String> missing = named.stream().filter(l -> !outside.contains(l)).toList();
            if (!named.isEmpty() && missing.size() * 2 > named.size()) {
                out.add(new Issue("图", WARN, name, "图里的名字多数在正文里没出现过，名字要和正文、代码一致：" + String.join("、", missing.stream().limit(6).toList())));
            }
        }
        return out.isEmpty() ? List.of(new Issue("图", PASS, null, spans.size() + " 张图，语法、编号、引用、节点数都合规")) : out;
    }

    private static boolean referenced(String outside, int n) {
        for (String line : outside.lines().toList()) {
            if (!CAPTION.matcher(line).matches() && Pattern.compile("图\\s*" + n + "(?!\\d)").matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** The number in the caption just below the block, else just above it; -1 when there is none. */
    private static int captionNumber(String text, List<String> lines, int[] span) {
        int endLine = (int) text.substring(0, span[1]).lines().count();
        int startLine = (int) text.substring(0, span[0]).lines().count() + (span[0] == 0 || text.charAt(span[0] - 1) == '\n' ? 1 : 0);
        for (int l = endLine; l < lines.size() && l < endLine + 3; l++) {
            if (lines.get(l).isBlank()) {
                continue;
            }
            Matcher c = CAPTION.matcher(lines.get(l));
            return c.matches() ? Integer.parseInt(c.group(1)) : below(lines, startLine);
        }
        return below(lines, startLine);
    }

    private static int below(List<String> lines, int startLine) {
        for (int l = startLine - 2; l >= 0 && l >= startLine - 4; l--) {
            if (lines.get(l).isBlank()) {
                continue;
            }
            Matcher c = CAPTION.matcher(lines.get(l));
            return c.matches() ? Integer.parseInt(c.group(1)) : -1;
        }
        return -1;
    }

    static Diagram parse(int index, String source) {
        List<String> lines = source.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("%%")).toList();
        List<String> problems = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        Set<String> nodes = new LinkedHashSet<>();
        if (lines.isEmpty()) {
            problems.add("代码块是空的");
            return new Diagram(index, source, -1, problems, labels, 0);
        }
        int first = 0; // a front-matter title: skip to the line after its closing ---
        if (lines.get(0).startsWith("---")) {
            first = lines.subList(1, lines.size()).indexOf("---") + 2;
        }
        if (first >= lines.size() || first < 0) {
            problems.add("没有图的类型");
            return new Diagram(index, source, -1, problems, labels, 0);
        }
        String head = lines.get(first);
        Matcher t = TYPE.matcher(head);
        if (!t.matches()) {
            problems.add("第一行要写图的类型（flowchart TB、sequenceDiagram、erDiagram、stateDiagram-v2 等），现在是「" + head + "」");
            return new Diagram(index, source, -1, problems, labels, 0);
        }
        List<String> body = lines.subList(first + 1, lines.size());
        if (head.startsWith("graph") || head.startsWith("flowchart")) {
            flow(body, problems, labels, nodes);
        } else if (head.equals("sequenceDiagram")) {
            sequence(body, problems, labels, nodes);
        } else if (head.equals("erDiagram")) {
            er(body, problems, labels, nodes);
        } else if (head.startsWith("stateDiagram")) {
            state(body, problems, labels, nodes);
        } else if (body.isEmpty()) {
            problems.add("图里没有内容");
        }
        return new Diagram(index, source, -1, problems, labels, nodes.size());
    }

    private static void flow(List<String> body, List<String> problems, Set<String> labels, Set<String> nodes) {
        int subgraphs = 0;
        for (String line : body) {
            if (!balanced(line)) {
                problems.add("括号或引号不成对：" + line);
                continue;
            }
            if (line.startsWith("subgraph")) {
                subgraphs++;
                Matcher m = Pattern.compile("^subgraph\\s+([\\w-]+)(?:\\s*\\[\"?([^\\]\"]*)\"?\\])?").matcher(line);
                if (m.find() && m.group(2) != null) {
                    labels.add(m.group(2));
                }
                continue;
            }
            if (line.equals("end")) {
                if (--subgraphs < 0) {
                    problems.add("多了一个 end");
                    subgraphs = 0;
                }
                continue;
            }
            if (FLOW_STMT.matcher(line).matches()) {
                continue;
            }
            String[] parts = line.split(EDGE.pattern());
            boolean edge = EDGE.matcher(line).find();
            if (!edge && !FLOW_NODE.matcher(line).matches()) {
                problems.add("无法识别的语句：" + line);
                continue;
            }
            if (edge && (line.matches(".*(-->|---|==>|-\\.->)\\s*$") || parts.length < 2 || parts[0].isBlank())) {
                problems.add("连线缺少一端的节点：" + line);
                continue;
            }
            for (String part : parts) {
                for (String piece : part.split("&")) {
                    String p = piece.replaceAll("^\\s*\\|[^|]*\\|", "").replaceAll("\\|[^|]*\\|", "").strip();
                    if (p.isEmpty()) {
                        continue;
                    }
                    Matcher n = FLOW_NODE.matcher(p);
                    if (!n.matches()) {
                        problems.add("无法识别的节点：" + p);
                        continue;
                    }
                    nodes.add(n.group(1));
                    for (int g = 2; g <= 5; g++) {
                        if (n.group(g) != null && !n.group(g).isBlank()) {
                            labels.add(n.group(g).strip());
                        }
                    }
                    if (n.group(2) == null && n.group(3) == null && n.group(4) == null && n.group(5) == null) {
                        labels.add(n.group(1));
                    }
                }
            }
        }
        if (subgraphs > 0) {
            problems.add("subgraph 缺少对应的 end");
        }
    }

    private static void sequence(List<String> body, List<String> problems, Set<String> labels, Set<String> nodes) {
        Deque<String> open = new ArrayDeque<>();
        Set<String> undeclared = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        for (String line : body) {
            Matcher part = SEQ_PART.matcher(line);
            Matcher msg = SEQ_MSG.matcher(line);
            if (part.matches()) {
                nodes.add(part.group(2));
                declared.add(part.group(2));
                labels.add(part.group(3) != null ? part.group(3).strip() : part.group(2));
            } else if (msg.matches()) {
                for (String n : List.of(msg.group(1), msg.group(3))) {
                    if (nodes.add(n) && labels.stream().noneMatch(l -> l.equals(n))) {
                        undeclared.add(n);
                    }
                }
            } else if (SEQ_OPEN.matcher(line).matches()) {
                open.push(line);
            } else if (line.equals("end")) {
                if (open.isEmpty()) {
                    problems.add("多了一个 end");
                } else {
                    open.pop();
                }
            } else if (!SEQ_MID.matcher(line).matches() && !SEQ_OTHER.matcher(line).matches()) {
                problems.add("无法识别的语句：" + line);
            }
        }
        if (!open.isEmpty()) {
            problems.add("「" + open.peek().split("\\s")[0] + "」缺少对应的 end");
        }
        undeclared.stream().filter(n -> !declared.contains(n)).forEach(labels::add); // named only in a message: its id is its name
    }

    private static void er(List<String> body, List<String> problems, Set<String> labels, Set<String> nodes) {
        boolean inEntity = false;
        for (String line : body) {
            Matcher rel = ER_REL.matcher(line);
            Matcher open = ER_OPEN.matcher(line);
            if (inEntity) {
                inEntity = !line.equals("}");
            } else if (rel.matches()) {
                nodes.add(rel.group(1));
                nodes.add(rel.group(2));
            } else if (open.matches()) {
                nodes.add(open.group(1));
                inEntity = true;
            } else {
                problems.add("无法识别的语句：" + line);
            }
        }
        if (inEntity) {
            problems.add("实体的 { 缺少对应的 }");
        }
        labels.addAll(nodes);
    }

    private static void state(List<String> body, List<String> problems, Set<String> labels, Set<String> nodes) {
        int open = 0;
        for (String line : body) {
            Matcher e = STATE_EDGE.matcher(line);
            if (e.matches()) {
                for (int g = 1; g <= 2; g++) {
                    if (!e.group(g).equals("[*]")) {
                        nodes.add(e.group(g));
                    }
                }
            } else if (line.matches("^state\\s+.+\\{$")) {
                open++;
            } else if (line.equals("}")) {
                if (--open < 0) {
                    problems.add("多了一个 }");
                    open = 0;
                }
            } else if (!line.matches("^(state\\s+\"[^\"]+\"\\s+as\\s+\\S+|state\\s+\\S+\\s*(<<\\w+>>)?|[\\w.-]+\\s*:\\s*.+|note\\s.+|--|direction\\s+\\w+|end note|title\\b.*)$")) {
                problems.add("无法识别的语句：" + line);
            }
        }
        if (open > 0) {
            problems.add("state 的 { 缺少对应的 }");
        }
        labels.addAll(nodes);
    }

    /** Brackets and double quotes pair up on the line. */
    private static boolean balanced(String line) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '[' || c == '(' || c == '{') {
                    stack.push(c);
                } else if (c == ']' || c == ')' || c == '}') {
                    char want = c == ']' ? '[' : c == ')' ? '(' : '{';
                    if (stack.isEmpty() || stack.pop() != want) {
                        return false;
                    }
                }
            }
        }
        return stack.isEmpty() && !quoted;
    }
}
