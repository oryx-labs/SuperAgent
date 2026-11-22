package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the flow reads back from git in one call each: a snapshot of the worktree, and the changes since the start. */
class WorkspaceSnapshotTest {

    @TempDir
    Path dir;

    private final Git git = new Git(Map.of("PATH", System.getenv("PATH")));

    @Test
    void aReviewersEditsRenamesAndNewFilesAreSeenAndUndoneButTheCliSessionFilesAreNot() throws Exception {
        HarnessState s = repo();
        Workspace ws = new Workspace(git);
        Files.writeString(dir.resolve("a.txt"), "edited\n"); // the developer's work, not yet committed
        Workspace.Snapshot before = ws.snapshot(s);
        assertThat(before.head()).matches("[0-9a-f]{40}");

        Files.createDirectories(dir.resolve(".superagent"));
        Files.writeString(dir.resolve(".superagent/session.json"), "{}");
        assertThat(ws.restore(s, before)).isFalse(); // the CLI's own files don't count

        Files.writeString(dir.resolve("a.txt"), "the reviewer's\n");
        git.text(dir, "mv", "with space.txt", "moved file.txt");
        Files.writeString(dir.resolve("new note.md"), "scratch\n");
        assertThat(ws.restore(s, before)).isTrue();
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("a\n"); // back to the commit: a reviewer only reads
        assertThat(dir.resolve("with space.txt")).exists();
        assertThat(dir.resolve("moved file.txt")).doesNotExist();
        assertThat(dir.resolve("new note.md")).doesNotExist();
        assertThat(dir.resolve(".superagent/session.json")).exists();
    }

    @Test
    void eachChangedFileKeepsItsOwnPatchARenameIncluded() throws Exception {
        HarnessState s = repo();
        Files.writeString(dir.resolve("a.txt"), "a\nmore\n");
        git.text(dir, "mv", "with space.txt", "moved file.txt");
        Files.writeString(dir.resolve("b.txt"), "diff --git looks like a header but is content\n");
        git.text(dir, "add", "-A");
        git.text(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "work");

        new Workspace(git).recordChanges(s);

        assertThat(s.files).extracting(HarnessState.ChangedFile::path)
                .containsExactly("a.txt", "b.txt", "with space.txt => moved file.txt");
        assertThat(s.patches.get("a.txt")).startsWith("diff --git a/a.txt b/a.txt").contains("+more").doesNotContain("b.txt");
        assertThat(s.patches.get("b.txt")).startsWith("diff --git a/b.txt").contains("+diff --git looks like a header");
        assertThat(s.patches.get("with space.txt => moved file.txt")).contains("rename to moved file.txt");
        assertThat(s.patches.values()).allMatch(p -> p.endsWith("\n"));
    }

    private HarnessState repo() throws Exception {
        git.text(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("a.txt"), "a\n");
        Files.writeString(dir.resolve("with space.txt"), "b\n");
        git.text(dir, "add", "-A");
        git.text(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        HarnessState s = new HarnessState();
        s.dir = dir.toString();
        s.baseSha = git.text(dir, "rev-parse", "HEAD");
        return s;
    }
}
