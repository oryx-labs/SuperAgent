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

    /** How a local delivery went: merged as {@code commit}, or stopped for the user with {@code problem}. */
    record Merge(String commit, String problem, boolean rebased) {
    }

    /**
     * Rebases the task's branch onto where the local branch is now and squash-merges it there as one commit. Stops
     * (touching nothing of the user's) on a conflict, uncommitted changes in the way, or the repository having moved
     * to another branch. {@code recheck} runs the checks again after a rebase; false stops the merge.
     */
    Merge mergeLocal(HarnessState s, String message, java.util.function.BooleanSupplier recheck) {
        Path root = Path.of(s.repoRoot);
        Path dir = Path.of(s.dir);
        synchronized (REPO_LOCKS.computeIfAbsent(s.repoRoot, k -> new Object())) {
            String tip = git.text(root, "rev-parse", s.baseBranch);
            boolean rebased = false;
            if (!tip.equals(s.baseSha)) {
                Git.Result rebase = git.git(dir, 600, Map.of(), "-c", "user.name=" + AGENT_NAME, "-c",
                        "user.email=" + AGENT_EMAIL, "rebase", s.baseBranch);
                if (!rebase.ok()) {
                    List<String> conflicts = git.git(dir, "diff", "--name-only", "--diff-filter=U").out().lines().toList();
                    git.git(dir, "rebase", "--abort");
                    return new Merge(null, "合并到 " + s.baseBranch + " 时有冲突：" + String.join("、", conflicts)
                            + "。这个分支在任务期间被别的改动更新过，和这次的改动碰到了同一处。", false);
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
            git.git(root, "worktree", "remove", "--force", s.dir);
            git.git(root, "branch", "-D", s.branch);
            return new Merge(sha, null, rebased);
        }
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
