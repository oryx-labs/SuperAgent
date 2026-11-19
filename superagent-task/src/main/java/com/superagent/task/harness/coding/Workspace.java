package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a coding task's changes are made and how they leave: a worktree off a local repository (squash-merged back
 * into the branch it started from), or a fresh clone of a remote one (pushed to a new branch). Every task gets its
 * own; nothing is shared with another task.
 */
final class Workspace {

    static final String AGENT_NAME = "SuperAgent";
    static final String AGENT_EMAIL = "agent@superagent.local";

    /** One merge at a time into a local branch: the next task rebases onto what the last one merged. */
    private static final Map<String, Object> REPO_LOCKS = new ConcurrentHashMap<>();

    private final Git git;

    Workspace(Git git) {
        this.git = git;
    }

    static boolean isRemote(String repo) {
        String r = repo == null ? "" : repo.strip();
        return r.matches("(?i)(https?|ssh|git)://.+") || r.matches("[\\w.-]+@[\\w.-]+:.+");
    }

    // --- intake ---

    /** Checks the repository and pins what the task starts from; a problem is said plainly, for the user to fix. */
    void resolve(HarnessState s, String branch, List<Path> roots) {
        if (s.mode.equals("local")) {
            if (roots.isEmpty()) {
                throw new IllegalArgumentException("这家公司还没开放本地代码目录：请管理员在「系统设置 → 编码任务」里加上允许的目录，"
                        + "或者改用远程仓库地址");
            }
            Path given = Path.of(s.repo);
            if (!given.isAbsolute() || !Files.isDirectory(given)) {
                throw new IllegalArgumentException("找不到代码目录 " + s.repo + "：请写这台服务器上的完整路径");
            }
            Path path = real(given);
            Git.Result top = git.git(path, "rev-parse", "--show-toplevel");
            if (!top.ok()) {
                throw new IllegalArgumentException(path + " 不是 git 仓库：编码任务需要在 git 仓库里做（可以先 git init）");
            }
            Path repoRoot = real(Path.of(top.out().strip()));
            // Both the path and the repository it belongs to: a subdirectory of an allowed root can't sit in a repo above it.
            if (!under(path, roots) || !under(repoRoot, roots)) {
                throw new IllegalArgumentException(repoRoot + " 不在允许的代码目录里（" + roots.stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining("、")) + "）：请管理员在「系统设置 → 编码任务」里加上");
            }
            s.repoRoot = repoRoot.toString();
            Path root = Path.of(s.repoRoot);
            String current = git.text(root, "rev-parse", "--abbrev-ref", "HEAD");
            s.baseBranch = branch != null ? branch : current;
            if ("HEAD".equals(s.baseBranch)) {
                throw new IllegalArgumentException("仓库当前不在任何分支上（detached HEAD）：请先切到要合并回去的分支");
            }
            Git.Result sha = git.git(root, "rev-parse", "--verify", s.baseBranch + "^{commit}");
            if (!sha.ok()) {
                throw new IllegalArgumentException("仓库里没有分支 " + s.baseBranch);
            }
            s.baseSha = sha.out().strip();
        } else {
            s.baseBranch = branch; // a clone of the default branch tells which it is
        }
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("找不到代码目录 " + p);
        }
    }

    private static boolean under(Path p, List<Path> roots) {
        return roots.stream().anyMatch(r -> {
            try {
                return p.startsWith(r.toRealPath());
            } catch (IOException e) {
                return false; // a root that no longer exists allows nothing
            }
        });
    }

    /** Uncommitted changes in the user's own checkout, which a later merge might run into (a warning, not a stop). */
    List<String> localChanges(HarnessState s) {
        if (!"local".equals(s.mode)) {
            return List.of();
        }
        Git.Result r = git.git(Path.of(s.repoRoot), "status", "--porcelain");
        return r.ok() ? r.out().lines().filter(l -> !l.isBlank()).map(l -> l.substring(Math.min(3, l.length()))).toList()
                : List.of();
    }

    /** A remote is there and readable with these credentials (and has {@code branch}, when one is named). */
    void reachable(String repo, String branch, Map<String, String> auth) {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Git.Result r = git.git(tmp, 30, auth, "ls-remote", "--heads", repo);
        if (!r.ok()) {
            // git's own words stay in the log: they can describe whatever the server reached.
            org.slf4j.LoggerFactory.getLogger(Workspace.class).info("ls-remote {} failed: {}", repo, Git.tail(r.output(), 500));
            throw new IllegalArgumentException("读不到仓库 " + repo + "：请确认地址对、仓库存在，并且你授权的账号有权限访问");
        }
        if (branch != null && r.out().lines().noneMatch(l -> l.endsWith("refs/heads/" + branch))) {
            throw new IllegalArgumentException("仓库 " + repo + " 里没有分支 " + branch);
        }
    }

    // --- provisioning ---

    /** Makes the task's own working copy on its own branch, and returns where it is. */
    Path provision(HarnessState s, Path workRoot, String taskId, Map<String, String> auth) throws IOException {
        Path dir = workRoot.resolve("code").resolve(taskId);
        Files.createDirectories(dir.getParent());
        s.branch = "agent/" + taskId.substring(0, Math.min(8, taskId.length()));
        if (s.mode.equals("local")) {
            Path root = Path.of(s.repoRoot);
            if (!Files.isDirectory(dir)) {
                Git.Result add = git.git(root, "worktree", "add", "-B", s.branch, dir.toString(), s.baseSha);
                if (!add.ok()) {
                    throw new Git.GitException("worktree add", add.output());
                }
            }
        } else {
            if (!Files.isDirectory(dir.resolve(".git"))) {
                List<String> args = new ArrayList<>(List.of("clone", "--filter=blob:none"));
                if (s.baseBranch != null) {
                    args.addAll(List.of("--branch", s.baseBranch));
                }
                args.addAll(List.of(s.repo, dir.toString()));
                Git.Result clone = git.git(dir.getParent(), 1800, auth, args.toArray(String[]::new));
                if (!clone.ok()) {
                    throw new Git.GitException("clone", clone.output());
                }
            }
            s.baseBranch = s.baseBranch != null ? s.baseBranch : git.text(dir, "rev-parse", "--abbrev-ref", "HEAD");
            s.baseSha = git.text(dir, "rev-parse", "HEAD");
            git.text(dir, "checkout", "-B", s.branch);
        }
        s.dir = dir.toString();
        return dir;
    }

    // --- recording the work ---

    /** Commits whatever the agent left uncommitted, so the branch holds all of it; false when there was nothing. */
    boolean commitAll(HarnessState s, String message) {
        Path dir = Path.of(s.dir);
        git.text(dir, "add", "-A");
        if (git.git(dir, "diff", "--cached", "--quiet").ok()) {
            return false;
        }
        git.text(dir, "-c", "user.name=" + AGENT_NAME, "-c", "user.email=" + AGENT_EMAIL, "commit", "-q", "-m", message);
        return true;
    }

    /** The files changed since the task started, with their line counts, and each one's diff (capped). */
    void recordChanges(HarnessState s) {
        Path dir = Path.of(s.dir);
        List<HarnessState.ChangedFile> files = new ArrayList<>();
        for (String line : git.text(dir, "diff", "--numstat", s.baseSha + "..HEAD").lines().toList()) {
            String[] p = line.split("\t", 3);
            if (p.length == 3) {
                files.add(new HarnessState.ChangedFile(p[2], num(p[0]), num(p[1])));
            }
        }
        s.files = files;
        s.patches.clear();
        int budget = 400_000;
        for (HarnessState.ChangedFile f : files) {
            if (budget <= 0) {
                break;
            }
            String patch = git.git(dir, "diff", s.baseSha + "..HEAD", "--", f.path()).out();
            String kept = patch.length() > 40_000 ? patch.substring(0, 40_000) + "\n…（太长，只显示开头）" : patch;
            s.patches.put(f.path(), kept);
            budget -= kept.length();
        }
    }

    /** The worktree as it stands: HEAD and what {@code git status} lists (the CLIs' own session files aside). */
    record Snapshot(String head, java.util.Set<String> status) {
    }

    Snapshot snapshot(HarnessState s) {
        Path dir = Path.of(s.dir);
        return new Snapshot(git.text(dir, "rev-parse", "HEAD"), status(dir));
    }

    private java.util.Set<String> status(Path dir) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String line : git.git(dir, "status", "--porcelain", "--untracked-files=all").out().lines().toList()) {
            if (line.length() > 3 && !line.substring(3).startsWith(".superagent/")) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Puts the worktree back as {@code before} had it (a reviewer only reads): its commits dropped, its edits undone,
     * the files it added deleted. False when it had changed nothing.
     */
    boolean restore(HarnessState s, Snapshot before) {
        Path dir = Path.of(s.dir);
        java.util.Set<String> now = status(dir);
        if (git.text(dir, "rev-parse", "HEAD").equals(before.head()) && now.equals(before.status())) {
            return false;
        }
        git.text(dir, "reset", "-q", "--hard", before.head());
        for (String line : status(dir)) {
            if (line.startsWith("?? ") && !before.status().contains(line)) {
                Path added = dir.resolve(line.substring(3)).normalize();
                if (added.startsWith(dir)) {
                    try {
                        Files.deleteIfExists(added);
                    } catch (IOException e) {
                        throw new IllegalStateException("could not undo the reviewer's file " + added + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        return true;
    }

    /** The whole change, for the reviewer (capped). */
    String diff(HarnessState s, int maxChars) {
        String d = git.git(Path.of(s.dir), "diff", s.baseSha + "..HEAD").out();
        return d.length() <= maxChars ? d : d.substring(0, maxChars) + "\n…（改动太长，后面没有贴出；需要时用 git diff 自己看）";
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0; // a binary file
        }
    }

    // --- delivery ---

    /**
     * How a local delivery went: merged as {@code commit}, or stopped for the user with {@code problem}; {@code conflicts}
     * (neither): the rebase ran into these files, for the flow to resolve.
     */
    record Merge(String commit, String problem, boolean rebased, List<String> conflicts) {
        Merge(String commit, String problem, boolean rebased) {
            this(commit, problem, rebased, List.of());
        }
    }

    /**
     * Rebases the task's branch onto where the local branch is now and squash-merges it there as one commit. Stops
     * (touching nothing of the user's) on a conflict, uncommitted changes in the way, or the repository having moved
     * to another branch. {@code recheck} runs the checks again after a rebase; false stops the merge.
     */
    Merge mergeLocal(HarnessState s, String message, java.util.function.BooleanSupplier recheck) {
        return mergeLocal(s, message, recheck, sha -> { });
    }

    /**
     * As above; {@code committed} gets the merge commit right after it is made, before the worktree is cleaned up,
     * so the task records it while shutdown still waits on this merge. A merge already recorded is not made again.
     */
    Merge mergeLocal(HarnessState s, String message, java.util.function.BooleanSupplier recheck,
                     java.util.function.Consumer<String> committed) {
        MergeGuard.enter();
        try {
            return merge(s, message, recheck, committed);
        } finally {
            MergeGuard.exit();
        }
    }

    /** The lock local merges into {@code repoRoot} take, one at a time. */
    static Object repoLock(String repoRoot) {
        return REPO_LOCKS.computeIfAbsent(repoRoot, k -> new Object());
    }

    private Merge merge(HarnessState s, String message, java.util.function.BooleanSupplier recheck,
                        java.util.function.Consumer<String> committed) {
        Path root = Path.of(s.repoRoot);
        Path dir = Path.of(s.dir);
        synchronized (repoLock(s.repoRoot)) {
            if (s.deliveryResult != null) { // merged before a restart; only the clean-up may be left
                git.git(root, "worktree", "remove", "--force", s.dir);
                git.git(root, "branch", "-D", s.branch);
                return new Merge(s.deliveryResult, null, false);
            }
            String tip = git.text(root, "rev-parse", s.baseBranch);
            boolean rebased = false;
            if (!tip.equals(s.baseSha)) {
                Git.Result rebase = git.git(dir, 600, Map.of(), "-c", "user.name=" + AGENT_NAME, "-c",
                        "user.email=" + AGENT_EMAIL, "rebase", s.baseBranch);
                if (!rebase.ok()) {
                    List<String> conflicts = unmerged(dir);
                    git.git(dir, "rebase", "--abort");
                    if (!conflicts.isEmpty()) {
                        return new Merge(null, null, false, conflicts);
                    }
                    return new Merge(null, "变基到 " + s.baseBranch + " 失败：" + Git.tail(rebase.output(), 500), false);
                }
                s.baseSha = tip;
                rebased = true;
                if (!recheck.getAsBoolean()) {
                    return new Merge(null, "变基到 " + s.baseBranch + " 最新提交后，验证没有通过，所以没有合并。", true);
                }
            }
            String current = git.text(root, "rev-parse", "--abbrev-ref", "HEAD");
            if (!current.equals(s.baseBranch)) {
                return new Merge(null, "你的项目现在在分支 " + current + " 上，不是任务开始时的 " + s.baseBranch
                        + "。切回 " + s.baseBranch + " 后回复「继续」，就会合并进去。", rebased);
            }
            Git.Result merge = git.git(root, "merge", "--squash", s.branch);
            if (!merge.ok()) {
                git.git(root, "merge", "--abort"); // nothing was merged; the user's own changes stay as they were
                return new Merge(null, "没能合并进 " + s.baseBranch + "：" + Git.tail(merge.output(), 500)
                        + "（多半是你本地有未提交的改动和这次改动碰到了同一个文件：先提交或暂存它们，再回复「继续」）", rebased);
            }
            Git.Result commit = git.git(root, "commit", "-q", "-m", message);
            if (!commit.ok()) {
                commit = git.git(root, "-c", "user.name=" + AGENT_NAME, "-c", "user.email=" + AGENT_EMAIL,
                        "commit", "-q", "-m", message);
                if (!commit.ok()) {
                    git.git(root, "reset", "--merge");
                    return new Merge(null, "没能提交合并：" + Git.tail(commit.output(), 500), rebased);
                }
            }
            String sha = git.text(root, "rev-parse", "HEAD");
            committed.accept(sha);
            git.git(root, "worktree", "remove", "--force", s.dir);
            git.git(root, "branch", "-D", s.branch);
            return new Merge(sha, null, rebased);
        }
    }

    // --- resolving a conflicted rebase, in the task's own worktree (the user's checkout is never touched) ---

    /**
     * A commit of the task's that didn't apply cleanly: which one, the files in conflict with their conflicted parts,
     * and what each side did ({@code baseLog}: what landed on the base branch meanwhile; {@code taskLog}: the task's).
     */
    record Conflict(String commit, List<String> files, Map<String, String> hunks, String baseLog, String taskLog) {
    }

    /** The outcome of {@code rebase --continue}: done, the next commit in conflict, or git's complaint. */
    record Continued(boolean done, Conflict next, String error) {
    }

    boolean rebasing(HarnessState s) {
        Path dir = Path.of(s.dir);
        for (String what : List.of("rebase-merge", "rebase-apply")) {
            Path p = Path.of(git.text(dir, "rev-parse", "--git-path", what));
            if (Files.isDirectory(p.isAbsolute() ? p : dir.resolve(p))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts rebasing the task's branch onto where the base branch is now and leaves it stopped at the conflict (null:
     * nothing to rebase, or it went cleanly). A rebase already under way is picked up where it stopped. The branch is
     * squashed into one commit first: it is squash-merged anyway, and so a conflict is resolved once, not once for every
     * commit of the task that touched the same place.
     */
    Conflict startRebase(HarnessState s) {
        if (rebasing(s)) {
            return conflict(s);
        }
        String tip = git.text(Path.of(s.repoRoot), "rev-parse", s.baseBranch);
        if (tip.equals(s.baseSha)) {
            return null;
        }
        s.rebaseOnto = tip;
        if (s.preRebaseHead == null) {
            s.preRebaseHead = git.text(Path.of(s.dir), "rev-parse", "HEAD");
            s.preRebaseBase = s.baseSha;
        }
        squash(s);
        Git.Result rebase = git.git(Path.of(s.dir), 600, Map.of(), "-c", "user.name=" + AGENT_NAME, "-c",
                "user.email=" + AGENT_EMAIL, "rebase", tip);
        if (rebase.ok()) {
            finishRebase(s);
            return null;
        }
        if (unmerged(Path.of(s.dir)).isEmpty()) {
            abortRebase(s);
            throw new Git.GitException("rebase", rebase.output());
        }
        return conflict(s);
    }

    private void squash(HarnessState s) {
        Path dir = Path.of(s.dir);
        String subjects = git.text(dir, "log", "--reverse", "--format=- %s", s.baseSha + "..HEAD");
        if (subjects.lines().count() < 2) {
            return;
        }
        git.text(dir, "reset", "-q", "--soft", s.baseSha);
        git.text(dir, "-c", "user.name=" + AGENT_NAME, "-c", "user.email=" + AGENT_EMAIL, "commit", "-q", "-m",
                "本任务的改动（合并前压成一个提交）\n\n" + subjects);
    }

    private Conflict conflict(HarnessState s) {
        Path dir = Path.of(s.dir);
        List<String> files = unmerged(dir);
        Map<String, String> hunks = new java.util.LinkedHashMap<>();
        int budget = 30_000;
        for (String f : files) {
            String h = hunks(dir.resolve(f), 6_000);
            if (budget > 0 && !h.isEmpty()) {
                hunks.put(f, h);
                budget -= h.length();
            }
        }
        String onto = s.rebaseOnto == null ? s.baseBranch : s.rebaseOnto;
        String commit = git.git(dir, "log", "-1", "--format=%h %s", "REBASE_HEAD").out().strip();
        String baseLog = git.git(dir, "log", "--format=%h %s%n%b", s.baseSha + ".." + onto, "-n", "30").out().strip();
        String taskLog = git.git(dir, "log", "--format=%h %s%n%b", onto + ".." + s.branch, "-n", "30").out().strip();
        return new Conflict(commit, files, hunks, baseLog, taskLog);
    }

    /** The conflicted parts of a file, each with a few lines around it (capped). */
    private static String hunks(Path file, int maxChars) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | java.io.UncheckedIOException e) {
            return ""; // deleted on one side, or not text
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("<<<<<<<")) {
                continue;
            }
            int end = i;
            while (end < lines.size() && !lines.get(end).startsWith(">>>>>>>")) {
                end++;
            }
            int from = Math.max(0, i - 3), to = Math.min(lines.size() - 1, end + 3);
            sb.append("@@ 第 ").append(from + 1).append(" 行起\n");
            for (int k = from; k <= to; k++) {
                sb.append(lines.get(k)).append("\n");
            }
            i = end;
        }
        return sb.length() <= maxChars ? sb.toString() : sb.substring(0, maxChars) + "\n…（太长，后面没有贴出）";
    }

    /**
     * The files still holding conflict markers. A bare {@code =======} line counts only when there are more of them
     * than either side had (a Markdown heading underline is not a marker).
     */
    List<String> markers(HarnessState s, List<String> files) {
        Path dir = Path.of(s.dir);
        List<String> left = new ArrayList<>();
        for (String f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(dir.resolve(f));
            } catch (IOException | java.io.UncheckedIOException e) {
                continue;
            }
            boolean marked = lines.stream().anyMatch(l -> l.matches("^(<{7}|>{7})( .*)?$"));
            long rules = lines.stream().filter(l -> l.equals("=======")).count();
            if (marked || rules > 0 && rules > Math.max(rules(dir, s.rebaseOnto, f), rules(dir, "REBASE_HEAD", f))) {
                left.add(f);
            }
        }
        return left;
    }

    private long rules(Path dir, String rev, String file) {
        if (rev == null) {
            return 0;
        }
        Git.Result r = git.git(dir, "show", rev + ":" + file);
        return r.ok() ? r.out().lines().filter(l -> l.equals("=======")).count() : 0;
    }

    /** Stages the resolution and goes on with the rebase (a commit the resolution left empty is skipped). */
    Continued continueRebase(HarnessState s) {
        Path dir = Path.of(s.dir);
        if (!rebasing(s)) {
            return finished(s); // the agent ran it itself
        }
        git.text(dir, "add", "-A");
        boolean empty = git.git(dir, "diff", "--cached", "--quiet").ok();
        Git.Result r = git.git(dir, 600, Map.of("GIT_EDITOR", "true"), "-c", "user.name=" + AGENT_NAME, "-c",
                "user.email=" + AGENT_EMAIL, "rebase", empty ? "--skip" : "--continue");
        if (!rebasing(s)) {
            return finished(s);
        }
        if (!unmerged(dir).isEmpty()) {
            return new Continued(false, conflict(s), null);
        }
        return new Continued(false, null, Git.tail(r.output(), 800));
    }

    private Continued finished(HarnessState s) {
        Path dir = Path.of(s.dir);
        if (s.rebaseOnto == null || !git.git(dir, "merge-base", "--is-ancestor", s.rebaseOnto, "HEAD").ok()) {
            s.rebaseOnto = null;
            return new Continued(false, null, "变基被中止了，分支没有接到 " + s.baseBranch + " 的最新提交上");
        }
        finishRebase(s);
        return new Continued(true, null, null);
    }

    private void finishRebase(HarnessState s) {
        s.baseSha = s.rebaseOnto;
        s.rebaseOnto = null;
    }

    /**
     * Puts the task's branch back as it was before the resolve step rebased it: a rebase under way is aborted, and one
     * that went through (with any fixes after it) is reset away.
     */
    void rollbackRebase(HarnessState s) {
        abortRebase(s);
        if (s.preRebaseHead != null) {
            git.text(Path.of(s.dir), "reset", "-q", "--hard", s.preRebaseHead);
            s.baseSha = s.preRebaseBase;
        }
        keepRebase(s);
    }

    /** The resolved rebase stands: nothing to go back to any more. */
    void keepRebase(HarnessState s) {
        s.preRebaseHead = null;
        s.preRebaseBase = null;
    }

    /** Aborts a rebase under way (the branch back to where that rebase started). */
    void abortRebase(HarnessState s) {
        if (rebasing(s)) {
            git.git(Path.of(s.dir), "rebase", "--abort");
        }
        s.rebaseOnto = null;
    }

    /** How the task's change now reads in {@code files}, on top of the base it was rebased onto (capped). */
    String diff(HarnessState s, List<String> files, int maxChars) {
        List<String> args = new ArrayList<>(List.of("diff", s.baseSha + "..HEAD", "--"));
        args.addAll(files);
        String d = git.git(Path.of(s.dir), args.toArray(String[]::new)).out();
        return d.length() <= maxChars ? d : d.substring(0, maxChars) + "\n…（太长，后面没有贴出）";
    }

    private List<String> unmerged(Path dir) {
        return git.git(dir, "diff", "--name-only", "--diff-filter=U").out().lines().filter(l -> !l.isBlank()).toList();
    }

    /** Pushes the task's branch; the problem when it couldn't (null when it went). */
    String push(HarnessState s, Map<String, String> auth) {
        Git.Result r = git.git(Path.of(s.dir), 600, auth, "push", "-u", "origin", "HEAD:refs/heads/" + s.branch);
        return r.ok() ? null : "推送分支失败：" + Git.tail(r.output(), 600);
    }

    /** Throws away a task's working copy (a stopped or deleted task); a local branch goes with its worktree. */
    void discard(HarnessState s) {
        if (s.dir == null) {
            return;
        }
        if ("local".equals(s.mode) && s.repoRoot != null) {
            git.git(Path.of(s.repoRoot), "worktree", "remove", "--force", s.dir);
            git.git(Path.of(s.repoRoot), "branch", "-D", s.branch);
        }
    }

    // --- auth ---

    /**
     * git's auth for a token, as environment variables (never on the command line, where ps would show it), scoped to
     * that one site and with redirects off, so git can't carry it anywhere else.
     */
    static Map<String, String> auth(String site, String connector, String token) {
        if (token == null || token.isBlank() || site == null) {
            return Map.of();
        }
        String user = PullRequests.GITHUB.equals(connector) ? "x-access-token" : "oauth2";
        String basic = Base64.getEncoder().encodeToString((user + ":" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Map.of("GIT_CONFIG_COUNT", "2",
                "GIT_CONFIG_KEY_0", "http." + site + "/.extraHeader", "GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic,
                "GIT_CONFIG_KEY_1", "http.followRedirects", "GIT_CONFIG_VALUE_1", "false");
    }

    /** The host of a remote, for both https and scp-like (git@host:path) addresses. */
    static String host(String repo) {
        String r = repo.strip();
        if (r.matches("[\\w.-]+@[\\w.-]+:.+")) {
            return r.substring(r.indexOf('@') + 1, r.indexOf(':')).toLowerCase(Locale.ROOT);
        }
        String h = URI.create(r).getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /** owner/name of a remote, without .git. */
    static String path(String repo) {
        String r = repo.strip();
        String p = r.matches("[\\w.-]+@[\\w.-]+:.+") ? r.substring(r.indexOf(':') + 1) : URI.create(r).getPath();
        p = p.replaceAll("^/+", "");
        return p.endsWith(".git") ? p.substring(0, p.length() - 4) : p;
    }
}
