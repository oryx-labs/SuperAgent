package com.superagent.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfirmPromptTest {

    @Test
    void beforeCreatingATaskTheAgentLooksForWorkOnTheSameRepositoryAndSaysWhatItWaitsFor() {
        String rules = TaskConfirmPrompt.rules("create_task", "");
        assertThat(rules).contains("list_tasks", "同一个代码仓库", "| 依赖 |", "after");
        assertThat(rules.split("\\| 依赖 \\|", -1)).hasSize(4); // a row in the general, coding and subtitle tables
        assertThat(rules).doesNotContain("%4$s");
    }

    @Test
    void aSubtitleTaskListsItsAudioReferencesAndTheModelsItWillUse() {
        String rules = TaskConfirmPrompt.rules("create_task", "");
        assertThat(rules).contains("| 任务类型 | ✅ 已确定 | 字幕 |", "| 音频 |", "| 参考资料 |", "| 听写模型 |", "| 校准模型 |",
                "subtitle_models");
        assertThat(TaskConfirmPrompt.rules("start_task", "")).contains("| 听写模型 |").doesNotContain("subtitle_models");
    }
}
