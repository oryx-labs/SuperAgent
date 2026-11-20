package com.superagent.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfirmPromptTest {

    @Test
    void beforeCreatingATaskTheAgentLooksForWorkOnTheSameRepositoryAndSaysWhatItWaitsFor() {
        String rules = TaskConfirmPrompt.rules("create_task", "");
        assertThat(rules).contains("list_tasks", "同一个代码仓库", "| 依赖 |", "after");
        assertThat(rules.split("\\| 依赖 \\|", -1)).hasSize(5); // a row in the general, coding, subtitle and writing tables
        assertThat(rules).doesNotContain("%4$s");
    }

    @Test
    void aSubtitleTaskListsItsAudioReferencesAndTheModelsItWillUse() {
        String rules = TaskConfirmPrompt.rules("create_task", "");
        assertThat(rules).contains("| 任务类型 | ✅ 已确定 | 字幕 |", "| 音频 |", "| 参考资料 |", "| 听写模型 |", "| 校准模型 |",
                "| ffmpeg |", "subtitle_models");
        assertThat(rules).contains("没有 ffmpeg 时标 ⚠️", "单声道 mp3");
        assertThat(TaskConfirmPrompt.rules("start_task", "")).contains("| 听写模型 |").doesNotContain("subtitle_models");
    }

    @Test
    void aWritingTaskNamesItsGenreAndTheAgentLooksUpWhatIsOnOffer() {
        String rules = TaskConfirmPrompt.rules("create_task", "");
        assertThat(rules).contains("| 任务类型 | ✅ 已确定 | 写作 |", "| 文体 |", "article_genres", "技术调研", "技术方案", "通用长文", "genre");
        // The SuperAgent's own tool has neither the lookup nor the field.
        assertThat(TaskConfirmPrompt.rules("start_task", "")).doesNotContain("| 文体 |").doesNotContain("article_genres");
    }
}
