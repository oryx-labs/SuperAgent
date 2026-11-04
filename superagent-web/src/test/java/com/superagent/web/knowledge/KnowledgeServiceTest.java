package com.superagent.web.knowledge;

import com.superagent.api.knowledge.KnowledgeEntryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeServiceTest {

    @TempDir
    Path tmp;

    @Test
    void foldersCanBeCreatedRenamedMovedAndDeleted() throws Exception {
        KnowledgeService kb = new KnowledgeService(tmp.toString());
        kb.createFolder("ada", "docs");
        kb.createFolder("ada", "docs/design");
        kb.createFolder("ada", "archive");
        Files.writeString(kb.ownerRoot("ada").resolve("docs/design/arch.md"), "# Arch");
        Files.writeString(kb.ownerRoot("ada").resolve("docs/.DS_Store"), "x");

        // Folders first, dot-files hidden.
        assertThat(kb.list("ada", "docs")).extracting(KnowledgeEntryDto::name).containsExactly("design");
        assertThat(kb.list("ada", "")).extracting(KnowledgeEntryDto::name).containsExactly("archive", "docs");

        assertThat(kb.rename("ada", "docs/design/arch.md", "architecture.md").path())
                .isEqualTo("docs/design/architecture.md");
        assertThat(kb.move("ada", "docs/design", "archive").path()).isEqualTo("archive/design");
        assertThat(new String(kb.read("ada", "archive/design/architecture.md").bytes())).isEqualTo("# Arch");
        assertThat(kb.folders("ada")).containsExactly("", "archive", "archive/design", "docs");

        assertThatThrownBy(() -> kb.move("ada", "archive", "archive/design")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kb.createFolder("ada", "docs")).isInstanceOf(KnowledgeConflictException.class);

        kb.delete("ada", "archive");
        assertThat(kb.list("ada", "")).extracting(KnowledgeEntryDto::name).containsExactly("docs");
    }

    @Test
    void savingCreatesTheFolderAndNeverOverwritesUnlessAsked() throws Exception {
        KnowledgeService kb = new KnowledgeService(tmp.resolve("kb").toString());
        Path src = Files.writeString(Files.createDirectories(tmp.resolve("ws")).resolve("prd.md"), "v1");

        assertThat(kb.saveFile("ada", src, "研发/需求", null, false).path()).isEqualTo("研发/需求/prd.md");
        assertThat(kb.saveFile("ada", src, "研发/需求", null, false).path()).isEqualTo("研发/需求/prd (2).md");
        Files.writeString(src, "v2");
        assertThat(kb.saveFile("ada", src, "研发/需求", null, true).path()).isEqualTo("研发/需求/prd.md");
        assertThat(new String(kb.read("ada", "研发/需求/prd.md").bytes())).isEqualTo("v2");

        assertThat(kb.writeText("ada", "笔记", "hi.md", "你好", false).path()).isEqualTo("笔记/hi.md");
        assertThat(kb.writeText("ada", "笔记", "hi.md", "again", false).path()).isEqualTo("笔记/hi (2).md");
        assertThatThrownBy(() -> kb.writeText("ada", "笔记", "../x.md", "no", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathsCannotEscapeTheOwnersRoot() throws Exception {
        KnowledgeService kb = new KnowledgeService(tmp.toString());
        kb.createFolder("bob", "private");
        Files.writeString(tmp.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(kb.ownerRoot("ada").resolve("link"), tmp);

        assertThatThrownBy(() -> kb.read("ada", "../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kb.list("ada", "../bob")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kb.read("ada", "link/outside.txt")).isInstanceOf(KnowledgeNotFoundException.class);
        assertThat(kb.list("ada", "")).isEmpty(); // the symlink isn't listed either
        assertThatThrownBy(() -> kb.list("ada", "private")).isInstanceOf(KnowledgeNotFoundException.class); // bob's
        assertThat(KnowledgeService.ownerDirName("../../etc")).startsWith("u-");
    }
}
