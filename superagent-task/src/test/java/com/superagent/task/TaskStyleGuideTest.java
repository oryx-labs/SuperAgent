package com.superagent.task;

import com.superagent.core.writing.StyleGuideSource;
import com.superagent.core.writing.StyleLexicon;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStyleGuideTest {

    @Test
    void everyKindButWritingGetsTheStandardWhileItIsOn() {
        AtomicReference<String> guide = new AtomicReference<>("规范A");
        StyleGuideSource source = new StyleGuideSource() {
            @Override public Optional<String> compactGuide(boolean technical) { return Optional.ofNullable(guide.get()); }
            @Override public StyleLexicon lexicon() { return StyleLexicon.FALLBACK; }
        };
        for (String flow : new String[]{null, "general", "coding", "complex"}) {
            assertThat(TaskService.styleGuide(source, flow, "整理", "纪要")).isEqualTo("规范A");
        }
        assertThat(TaskService.styleGuide(source, "article", "调研", "写一份")).isNull();
        guide.set("规范B");
        assertThat(TaskService.styleGuide(source, "coding", "x", "y")).isEqualTo("规范B");
        guide.set(null);
        assertThat(TaskService.styleGuide(source, "coding", "x", "y")).isNull();
    }
}
