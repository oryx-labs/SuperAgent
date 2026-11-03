package com.superagent.core.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskKindClassifierTest {

    @Test
    void codingKeywordsWinOverOthers() {
        assertThat(TaskKindClassifier.classify("修复登录接口的 bug", "重构实现"))
                .isEqualTo(TaskKind.CODING);
        // Coding is checked first, so a brief mixing coding + report still classifies as coding.
        assertThat(TaskKindClassifier.classify("实现导出功能", "顺便写一份报告"))
                .isEqualTo(TaskKind.CODING);
    }

    @Test
    void nonCodingKindsClassifyByKeyword() {
        assertThat(TaskKindClassifier.classify("撰写发布文案", null)).isEqualTo(TaskKind.WRITING);
        assertThat(TaskKindClassifier.classify("生成月度报表", "统计各项指标")).isEqualTo(TaskKind.STATS);
        assertThat(TaskKindClassifier.classify("调研竞品方案", "对比优劣")).isEqualTo(TaskKind.RESEARCH);
    }

    @Test
    void unmatchedFallsBackToGeneral() {
        assertThat(TaskKindClassifier.classify("随便聊聊", "没有明确类型")).isEqualTo(TaskKind.GENERAL);
        assertThat(TaskKindClassifier.classify(null, null)).isEqualTo(TaskKind.GENERAL);
    }
}
