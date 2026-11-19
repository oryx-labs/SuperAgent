package com.superagent.agent.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfirmPromptTest {

    @Test
    void beforeCreatingATaskTheAgentLooksForWorkOnTheSameRepositoryAndSaysWhatItWaitsFor() {
        String rules = TaskConfirmPrompt.rules("create_task", "");
        assertThat(rules).contains("list_tasks", "同一个代码仓库", "| 依赖 |", "after");
        assertThat(rules.split("\\| 依赖 \\|", -1)).hasSize(3); // a row in both the general and the coding table
        assertThat(rules).doesNotContain("%4$s");
    }
}
