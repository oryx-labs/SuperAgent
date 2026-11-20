package com.superagent.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing and rendering the categorised memory document. */
class MemorySectionsTest {

    @Test
    void oldHeadingsMapOntoCategoriesAndLooseTextGoesToOther() {
        var sections = MemorySections.parse("# X 的记忆\n开头一句\n## 关于用户\n- a\n## 目标与项目\n- b\n## 用户偏好\n- c\n## 自定义\n- d");
        assertThat(sections).containsExactly(
                java.util.Map.entry("其他", "开头一句"),
                java.util.Map.entry("用户偏好", "- a\n- c"),
                java.util.Map.entry("项目理解", "- b"),
                java.util.Map.entry("自定义", "- d"));
        assertThat(MemorySections.render("X", sections)).startsWith("# X 的记忆\n\n## 其他\n开头一句\n\n## 用户偏好\n- a\n- c");
        assertThat(MemorySections.render("X", MemorySections.parse(" "))).isNull();
        assertThat(MemorySections.withOwned("项目理解", "## 踩过的坑")).isEqualTo("项目理解\n踩过的坑");
        assertThat(MemorySections.owned(null)).isEqualTo(List.of());
    }
}
