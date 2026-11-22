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

    @Test
    void aTechnicalResearchOrDesignDeliverableIsRecommendedAsAWritingTask() {
        assertThat(TaskConfirmPrompt.rules("create_task", "")).contains("技术调研或技术方案", "推荐「写作」");
        assertThat(TaskConfirmPrompt.rules("create_task", "")).doesNotContain("%5$s");
    }

    @Test
    void theCompactStandardIsInTheChatPromptOnlyWhileItIsOnAndFollowsTheSkill() {
        java.util.concurrent.atomic.AtomicReference<String> guide = new java.util.concurrent.atomic.AtomicReference<>("规范A");
        com.superagent.core.writing.StyleGuideSource source = new com.superagent.core.writing.StyleGuideSource() {
            @Override public java.util.Optional<String> compactGuide(boolean technical) {
                return java.util.Optional.ofNullable(guide.get()).map(g -> technical ? g + "+技术" : g);
            }
            @Override public com.superagent.core.writing.StyleLexicon lexicon() { return com.superagent.core.writing.StyleLexicon.FALLBACK; }
        };
        assertThat(ChatService.styleGuide(source, "助手", "你好")).isEqualTo("规范A");
        assertThat(ChatService.styleGuide(source, "助手", "写一份技术方案")).isEqualTo("规范A+技术");
        guide.set("规范B");
        assertThat(ChatService.styleGuide(source, "助手", "你好")).isEqualTo("规范B");
        guide.set(null);
        assertThat(ChatService.styleGuide(source, "助手", "你好")).isNull();
        assertThat(ChatService.styleGuide(null, "助手", "你好")).isNull();
    }

}
