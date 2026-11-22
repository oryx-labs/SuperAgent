package com.superagent.core.writing;

import com.superagent.core.skill.Skill;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChecksTest {

    private static final StyleLexicon LEX = StyleLexicon.FALLBACK;

    private static String level(List<TextChecks.Finding> f) {
        return f.get(0).level();
    }

    @Test
    void stockPhrasesWarnOnlyWhenDense() {
        String clean = "缓存把查询从 420 ms 降到 85 ms。过期时间是 60 秒。".repeat(20);
        String heavy = "值得注意的是，赋能业务，综上所述，全方位助力闭环。".repeat(10);
        assertThat(level(TextChecks.filler(clean, LEX))).isEqualTo("pass");
        assertThat(TextChecks.filler(heavy, LEX)).isNotEmpty().allMatch(f -> f.level().equals("warn"));
        assertThat(TextChecks.warnings("# 标题\n\n## 现状\n\n" + heavy, LEX)).anyMatch(f -> f.check().contains("AI 腔"));
    }

    @Test
    void concretenessFlagsEmptySectionsAndVagueClaimsAsWarningsOnTheGeneralPath() {
        assertThat(level(TextChecks.concreteness("# 方案\n\n## 现状\n\n用 `Cache` 缓存 60 秒。\n", LEX, "warn"))).isEqualTo("pass");
        List<TextChecks.Finding> bad = TextChecks.concreteness("# 方案\n\n## 现状\n\n现状比较复杂，需要重构，提升性能。\n", LEX, "warn");
        assertThat(bad).hasSizeGreaterThanOrEqualTo(2).allMatch(f -> f.level().equals("warn"));
        assertThat(TextChecks.concreteness("# 方案\n\n## 现状\n\n现状复杂。\n", LEX, "fail")).allMatch(f -> f.level().equals("fail"));
    }

    @Test
    void headingJumpsAndSecondTitlesWarn() {
        assertThat(level(TextChecks.headings("# 标题\n\n## 一\n\n### 二\n", "warn"))).isEqualTo("pass");
        List<TextChecks.Finding> bad = TextChecks.headings("# 标题\n\n### 跳级\n\n# 又一个标题\n", "warn");
        assertThat(bad).hasSize(2).allMatch(f -> f.level().equals("warn"));
    }

    @Test
    void theWordListComesFromTheSkillAndChangesWithIt() {
        String content = "说明\n\n```lexicon\n[banned]\n流转引擎\n[vague]\n变得更好\n```\n";
        StyleLexicon lex = StyleLexicon.of(List.of(skill("去 AI 味写作规范", content)), null);
        assertThat(lex.banned()).containsExactly("流转引擎");
        String text = "这个功能叫流转引擎。" + "平实的句子。".repeat(30);
        assertThat(TextChecks.bannedPhrases(text, lex, List.of(), false)).anyMatch(f -> f.level().equals("warn"));
        StyleLexicon changed = StyleLexicon.of(List.of(skill("去 AI 味写作规范", content.replace("流转引擎", "别的词"))), null);
        assertThat(level(TextChecks.bannedPhrases(text, changed, List.of(), false))).isEqualTo("pass");
    }

    @Test
    void aBrokenBlockNamesTheSkillAndAMissingSkillFallsBack() {
        assertThatThrownBy(() -> StyleLexicon.parse("我的规范", "```lexicon\n词没有小节\n```"))
                .isInstanceOf(StyleLexicon.StyleLexiconException.class).hasMessageContaining("我的规范");
        assertThatThrownBy(() -> StyleLexicon.parse("我的规范", "```lexicon\n[patterns]\n(未闭合\n```")).hasMessageContaining("我的规范");
        assertThat(StyleLexicon.parse("x", "没有词表块")).isNull();
        assertThat(StyleLexicon.of(List.of(), null)).isEqualTo(StyleLexicon.FALLBACK);
        assertThat(StyleLexicon.of(List.of(skill("去 AI 味写作规范", "没有块")), null)).isEqualTo(StyleLexicon.FALLBACK);
    }

    static Skill skill(String name, String content) {
        Instant now = Instant.now();
        return new Skill(UUID.randomUUID().toString(), name, "", null, content, "company", true, false, "system", "system", now, now);
    }
}
