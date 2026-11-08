package com.superagent.agent.files;

import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileKind;
import com.superagent.files.FileService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTaggerTest {

    @Test
    void thePromptCarriesTheVocabularyAndTheReplyIsReadEvenInsideACodeFence() {
        WorkFile f = new WorkFile("f1", "周报.md", WorkFileKind.DOCUMENT, "text/markdown", 10, WorkFile.INTERNAL, "f1",
                "bob", "dev", null, WorkFile.ORIGIN_DELIVERY, false, Instant.now(), Instant.now(), List.of(), "");
        String prompt = FileTagger.prompt(List.of(new FileService.Untagged(f, "本周完成了讲义", List.of("课程", "周报"))));
        assertThat(prompt).contains("已在用的标签：课程，周报").contains("id: f1").contains("本周完成了讲义");

        FileTagger tagger = new FileTagger(null, null, null, null, List.of(), false);
        assertThat(tagger.parse("好的：\n```json\n{\"f1\": [\"周报\", \"#讲义\", \"进展\", \"多余\"]}\n```"))
                .containsEntry("f1", List.of("周报", "讲义", "进展"));
        assertThatThrownBy(() -> tagger.parse("没法判断")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void asksForAFolderAmongThoseThatExistAndReadsTheAnswer() {
        WorkFile f = new WorkFile("f1", "Q4复盘.md", com.superagent.core.file.WorkFileKind.DOCUMENT, "text/markdown", 1,
                "internal", "f1", "ada", null, null, "chat", false, java.time.Instant.now(), java.time.Instant.now(),
                List.of("复盘"), "agent");
        String prompt = FileTagger.folderPrompt(List.of("项目/Q4"),
                List.of(new com.superagent.files.FileFolderService.Unfiled(f, "第四季度")));
        assertThat(prompt).contains("- 项目/Q4").contains("id: f1").contains("标签：复盘").contains("第四季度");
        FileTagger tagger = new FileTagger(null, null, null, null, List.of(), false);
        assertThat(tagger.parseFolders("```json\n{\"f1\": \"项目/Q4\", \"f2\": null}\n```"))
                .containsEntry("f1", "项目/Q4").doesNotContainKey("f2");
    }
}
