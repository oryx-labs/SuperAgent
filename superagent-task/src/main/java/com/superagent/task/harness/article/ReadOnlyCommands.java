package com.superagent.task.harness.article;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The only commands an investigation's platform side runs (re-checking the evidence log): a short list of readers,
 * joined at most by pipes, with every path inside the directory they run in. Anything else is refused, not run.
 * There is no shell: each stage is started directly with its own arguments and the pipeline is built here, so
 * quoting, globs and expansions never reach one. Git is told not to run external diff or text-conversion programs.
 */
final class ReadOnlyCommands {

    private static final Set<String> TOOLS = Set.of("cat", "head", "tail", "ls", "grep", "egrep", "fgrep", "rg", "wc", "sort", "uniq",
            "cut", "tr", "nl", "stat", "file", "du", "diff", "find", "git", "basename", "dirname", "pwd", "tree");
    private static final Set<String> GIT = Set.of("log", "show", "diff", "status", "blame", "grep", "ls-files", "rev-parse", "cat-file",
            "shortlog", "describe", "rev-list");
    private static final Set<String> FIND_WRITES = Set.of("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fls", "-fprintf");
    private static final int TIMEOUT_SECONDS = 20, OUTPUT_CHARS = 20_000;

    private ReadOnlyCommands() {
    }

    /** {@code refused}: why it was not run (null when it ran); {@code output} is the merged output, cut if long. */
    record Result(boolean ran, int exit, String output, String refused) {
    }

    /** The command split into stages of arguments, or why it can't be. */
    private record Parsed(List<List<String>> stages, String refusal) {
    }

    /** Why {@code command} may not run in {@code root} (empty: it may). */
    static Optional<String> refusal(String command, Path root) {
        Parsed p = parse(command);
        if (p.refusal() != null) {
            return Optional.of(p.refusal());
        }
        for (List<String> stage : p.stages()) {
            Optional<String> why = stage(stage, root);
            if (why.isPresent()) {
                return why;
            }
        }
        return Optional.empty();
    }

    private static Parsed parse(String command) {
        if (command == null || command.isBlank()) {
            return new Parsed(null, "命令是空的");
        }
        List<List<String>> stages = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '$' || c == '`' || c == '\n' || c == '\r' || c == '\\') {
                return new Parsed(null, "命令里有 shell 展开或换行（" + (c == '\n' ? "换行" : String.valueOf(c)) + "），只允许直接写命令和参数");
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    tok.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                has = true;
            } else if (c == ' ' || c == '\t') {
                if (has || !tok.isEmpty()) {
                    cur.add(tok.toString());
                    tok.setLength(0);
                    has = false;
                }
            } else if (c == '|') {
                if (has || !tok.isEmpty()) {
                    cur.add(tok.toString());
                    tok.setLength(0);
                    has = false;
                }
                if (cur.isEmpty() || (i + 1 < command.length() && command.charAt(i + 1) == '|')) {
                    return new Parsed(null, "管道两边都要有命令，不支持 ||");
                }
                stages.add(cur);
                cur = new ArrayList<>();
            } else if (c == ';' || c == '&' || c == '>' || c == '<' || c == '(' || c == ')' || c == '{' || c == '}') {
                return new Parsed(null, "不允许 " + c + "（写文件、重定向、串联命令都不行），只读命令之间只能用 |");
            } else {
                tok.append(c);
            }
        }
        if (quote != 0) {
            return new Parsed(null, "引号没有闭合");
        }
        if (has || !tok.isEmpty()) {
            cur.add(tok.toString());
        }
        if (cur.isEmpty()) {
            return new Parsed(null, "管道最后缺一个命令");
        }
        stages.add(cur);
        return new Parsed(stages, null);
    }

    private static Optional<String> stage(List<String> t, Path root) {
        String tool = t.get(0);
        if (!TOOLS.contains(tool)) {
            return Optional.of("「" + tool + "」不在只读命令的白名单里（可用：" + String.join(" ", TOOLS.stream().sorted().toList()) + "）");
        }
        if (tool.equals("git") && (t.size() < 2 || !GIT.contains(t.get(1)))) {
            return Optional.of("git 只允许 " + String.join("、", GIT.stream().sorted().toList()) + "，并且子命令要紧跟在 git 后面");
        }
        boolean greps = Set.of("grep", "egrep", "fgrep", "rg").contains(tool) || tool.equals("git") && t.get(1).equals("grep");
        int pattern = -1; // a search pattern such as "/api/v1" is text, not a path
        for (int i = 1; greps && i < t.size() && pattern < 0; i++) {
            if (t.get(i).equals("-e") && i + 1 < t.size()) {
                pattern = i + 1;
            } else if (!t.get(i).startsWith("-") && !(tool.equals("git") && i == 1)) {
                pattern = i;
            }
        }
        for (int i = 1; i < t.size(); i++) {
            String a = t.get(i);
            if (i == pattern) {
                continue;
            }
            if (tool.equals("find") && FIND_WRITES.contains(a)) {
                return Optional.of("find 的 " + a + " 会改文件或执行命令");
            }
            boolean writes = a.startsWith("--output") || a.startsWith("--pre") || a.startsWith("--compress-program") || a.startsWith("--ext-diff")
                    || a.startsWith("--textconv") || a.startsWith("--open-files-in-pager") || a.startsWith("--exec-path")
                    || a.startsWith("--hostname-bin") || (tool.equals("git") && a.matches("-[A-Za-z]*O.*"))
                    || ((tool.equals("sort") || tool.equals("tree")) && a.matches("-[A-Za-z]*o.*")) // -o, -ofile, -uo …
                    || (tool.equals("tail") && (a.equals("-f") || a.equals("-F") || a.startsWith("--follow")));
            if (writes) {
                return Optional.of("参数 " + a + " 会写文件、执行命令或一直等待");
            }
            String path = a.contains("=") ? a.substring(a.indexOf('=') + 1) : a;
            if (path.startsWith("~")) {
                return Optional.of("不允许用 ~ 指到用户目录：" + a);
            }
            if (!path.startsWith("-") && outside(root, path)) {
                return Optional.of("路径 " + a + " 在任务目录之外");
            }
        }
        return Optional.empty();
    }

    /** Whether {@code path} (relative to {@code root}) ends up outside it, by its spelling or, once it exists, by where links lead. */
    private static boolean outside(Path root, String path) {
        Path base = root.toAbsolutePath().normalize();
        Path p = base.resolve(path).normalize();
        if (!p.startsWith(base)) {
            return true;
        }
        try {
            return java.nio.file.Files.exists(p) && !p.toRealPath().startsWith(base.toRealPath());
        } catch (IOException e) {
            return true;
        }
    }

    static Result run(Path root, String command) {
        Optional<String> why = refusal(command, root);
        if (why.isPresent()) {
            return new Result(false, -1, "", why.get());
        }
        try {
            List<ProcessBuilder> builders = new ArrayList<>();
            List<List<String>> stages = parse(command).stages();
            for (int i = 0; i < stages.size(); i++) {
                List<String> argv = new ArrayList<>(stages.get(i));
                if (argv.get(0).equals("git") && Set.of("diff", "log", "show").contains(argv.get(1))) {
                    argv.addAll(2, List.of("--no-ext-diff", "--no-textconv"));
                }
                if (argv.get(0).equals("git")) {
                    argv.addAll(1, List.of("-c", "core.pager=cat", "-c", "core.fsmonitor=", "-c", "diff.external="));
                }
                ProcessBuilder b = new ProcessBuilder(argv).directory(root.toFile());
                b.environment().put("GIT_CONFIG_NOSYSTEM", "1");
                b.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
                b.environment().put("GIT_PAGER", "cat");
                b.environment().put("GIT_OPTIONAL_LOCKS", "0");
                b.environment().remove("GIT_EXTERNAL_DIFF");
                if (i < stages.size() - 1) {
                    b.redirectError(ProcessBuilder.Redirect.DISCARD);
                } else {
                    b.redirectErrorStream(true);
                }
                builders.add(b);
            }
            List<Process> procs = ProcessBuilder.startPipeline(builders);
            Process last = procs.get(procs.size() - 1);
            byte[] out = last.getInputStream().readNBytes(OUTPUT_CHARS * 4);
            boolean done = last.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            procs.forEach(Process::destroyForcibly);
            if (!done) {
                return new Result(false, -1, "", "超过 " + TIMEOUT_SECONDS + " 秒没有结束");
            }
            String text = new String(out, StandardCharsets.UTF_8);
            return new Result(true, last.exitValue(), text.length() > OUTPUT_CHARS ? text.substring(0, OUTPUT_CHARS) : text, null);
        } catch (IOException e) {
            return new Result(false, -1, "", "没能运行：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, "", "被打断");
        }
    }
}
