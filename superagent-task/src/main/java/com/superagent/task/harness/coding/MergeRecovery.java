package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After a restart, undoes a local delivery the old process left half done: a rebase stopped in the task's worktree,
 * or a squash-merge staged but not committed in the user's repository. Only what provably came from this task's
 * branch is undone (the delivery step then merges again); anything else is left alone and reported.
 */
final class MergeRecovery {

    private static final Pattern SQUASHED = Pattern.compile("(?m)^commit ([0-9a-f]{40})$");

    private final Git git;

    MergeRecovery(Git git) {
        this.git = git;
    }

    /** What was found that isn't this task's to undo, for the user (empty when there's nothing to say). */
    List<String> repair(HarnessState s) {
        List<String> foreign = new ArrayList<>();
        if (!"local".equals(s.mode) || s.repoRoot == null || s.branch == null || s.deliveryResult != null) {
            return foreign;
        }
        synchronized (Workspace.repoLock(s.repoRoot)) {
            if (s.dir != null && Files.isDirectory(Path.of(s.dir))) {
                repairRebase(Path.of(s.dir), s, foreign);
            }
            Path root = Path.of(s.repoRoot);
            if (Files.isDirectory(root)) {
                repairMerge(root, s, foreign);
            }
        }
        return foreign;
    }

    private void repairRebase(Path dir, HarnessState s, List<String> foreign) {
        for (String state : List.of("rebase-merge", "rebase-apply")) {
            Path headName = gitPath(dir, state).resolve("head-name");
            if (Files.isRegularFile(headName)) {
                String rebasing = read(headName);
                if (rebasing.equals("refs/heads/" + s.branch)) {
                    git.git(dir, "rebase", "--abort");
                } else {
                    foreign.add("任务工作目录 " + dir + " 停在变基一半（" + rebasing + "），不是这个任务的分支，没有动它。");
                }
                return;
            }
        }
    }

    private void repairMerge(Path root, HarnessState s, List<String> foreign) {
        Path mergeHead = gitPath(root, "MERGE_HEAD");
        if (Files.isRegularFile(mergeHead)) {
            if (ours(root, read(mergeHead), s)) {
                git.git(root, "merge", "--abort");
            } else {
                foreign.add("你的仓库 " + root + " 正停在一次合并中间（不是这个任务做的），没有动它；请先完成或放弃那次合并。");
            }
            return;
        }
        Path squash = gitPath(root, "SQUASH_MSG");
        boolean staged = !git.git(root, "diff", "--cached", "--quiet").ok();
        if (!Files.isRegularFile(squash) || !staged) {
            return;
        }
        List<String> commits = new ArrayList<>();
        Matcher m = SQUASHED.matcher(read(squash));
        while (m.find()) {
            commits.add(m.group(1));
        }
        if (!commits.isEmpty() && commits.stream().allMatch(c -> ours(root, c, s))) {
            git.git(root, "reset", "--merge");
        } else {
            foreign.add("你的仓库 " + root + " 里有一次没提交的 squash 合并（不是这个任务做的），没有动它；"
                    + "请先提交或放弃它，再回复「继续」。");
        }
    }

    /** On the task's branch and not already on the branch it merges into: only this task made it. */
    private boolean ours(Path root, String sha, HarnessState s) {
        return git.git(root, "merge-base", "--is-ancestor", sha, s.branch).ok()
                && (s.baseBranch == null || !git.git(root, "merge-base", "--is-ancestor", sha, s.baseBranch).ok());
    }

    private Path gitPath(Path dir, String name) {
        return dir.resolve(git.text(dir, "rev-parse", "--git-path", name));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }
}
