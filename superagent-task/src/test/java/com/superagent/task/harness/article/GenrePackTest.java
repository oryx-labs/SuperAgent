package com.superagent.task.harness.article;

import com.superagent.api.skill.SkillRequest;
import com.superagent.core.skill.Skill;
import com.superagent.skills.GenreSeeds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pack's configuration block: what loads, what is refused and how the message reads, and packs a company adds. */
class GenrePackTest {

    private static final String GOOD = """
            ---
            genre: postmortem
            research: investigate
            needs_repo: true
            word_range: [1000, 8000]
            required_sections:
              - [时间线]
              - [根因, 原因]
              - 改进措施
            checks: [code_refs, concreteness, banned_phrases]
            reviewers: [architect, reader]
            weights: 技术文章
            max_open_questions: 2
            ---
            正文给模型看。
            """;

    private static Skill skill(String name, String content) {
        Instant now = Instant.now();
        return new Skill(name, name, "说明", "写作", content, "company", true, false, "system", "system", now, now);
    }

    @Test
    void aValidBlockLoadsWithItsDefaults() {
        GenrePack p = GenrePack.parse("事故复盘", GOOD);
        assertThat(p.genre()).isEqualTo("postmortem");
        assertThat(p.investigates()).isTrue();
        assertThat(p.needsRepo()).isTrue();
        assertThat(p.minWords()).isEqualTo(1000);
        assertThat(p.requiredSections()).containsExactly(List.of("时间线"), List.of("根因", "原因"), List.of("改进措施"));
        assertThat(p.reviewers()).containsExactly("architect", "reader");
        assertThat(p.maxOpenQuestions()).isEqualTo(2);
        assertThat(GenrePack.body(GOOD)).isEqualTo("正文给模型看。");

        GenrePack minimal = GenrePack.parse("最小", "---\ngenre: x\nrequired_sections: [结论]\n---\n正文");
        assertThat(minimal.research()).isEqualTo("collect");
        assertThat(minimal.reviewers()).containsExactly("editor", "fact_checker", "reader");
        assertThat(minimal.minWords()).isEqualTo(500);
    }

    @Test
    void everyKindOfMistakeIsRefusedNamingTheSkillAndTheField() {
        String head = "---\ngenre: x\nrequired_sections: [结论]\n";
        assertRefused("---\ngenre: x\n---\n", "required_sections", "至少列一个");
        assertRefused(head.replace("required_sections: [结论]", "required_sections: []") + "---\n", "required_sections", "至少列一个");
        assertRefused(head + "colour: red\n---\n", "colour", "未知字段");
        assertRefused(head + "checks: [code_refs, magic]\n---\n", "checks", "未知检查项「magic」");
        assertRefused(head + "reviewers: [editor, oracle]\n---\n", "reviewers", "未知评审角色「oracle」");
        assertRefused(head + "reviewers: [editor, architect]\n---\n", "reviewers", "只能选一个");
        assertRefused(head + "reviewers: [reader]\n---\n", "reviewers", "editor 或 architect");
        assertRefused(head + "word_range: [9000, 100]\n---\n", "word_range", "0 < 最少 ≤ 最多");
        assertRefused(head + "word_range: 100\n---\n", "word_range", "两个整数");
        assertRefused(head + "research: peek\n---\n", "research", "collect 或 investigate");
        assertRefused(head + "needs_repo: maybe\n---\n", "needs_repo", "true 或 false");
        assertRefused(head + "weights: 小说\n---\n", "weights", "之一");
        assertRefused(head + "checks: [evidence_ids]\n---\n", "checks", "research: investigate");
        assertRefused("---\nrequired_sections: [结论]\n---\n", "genre", "必填");
        assertRefused("没有配置块", null, "没有用 --- 包起来");
        assertRefused("---\ngenre: [unclosed\n---\n", null, "不是合法的 YAML");
    }

    private static void assertRefused(String content, String field, String problem) {
        assertThatThrownBy(() -> GenrePack.parse("事故复盘", content)).isInstanceOf(GenrePackException.class)
                .hasMessageContaining("「事故复盘」").hasMessageContaining(field == null ? "" : "字段 " + field).hasMessageContaining(problem);
    }

    @Test
    void aCompanyAddedGenreIsFoundByNameOrIdWithoutAnyCodeForIt() {
        GenrePacks packs = new GenrePacks(() -> List.of(skill("事故复盘", GOOD), skill("普通 skill", "没有配置块"),
                skill("坏的", "---\ngenre: bad\ncolour: red\nrequired_sections: [a]\n---\n")));
        assertThat(packs.find("事故复盘")).get().satisfies(l -> assertThat(l.pack().genre()).isEqualTo("postmortem"));
        assertThat(packs.find("postmortem")).isPresent();
        assertThat(packs.find("没有这个")).isEmpty();
        assertThat(packs.find("普通 skill")).isEmpty(); // a skill without the block is not a pack
        assertThatThrownBy(() -> packs.find("坏的")).isInstanceOf(GenrePackException.class).hasMessageContaining("字段 colour");
        assertThat(packs.describe()).anyMatch(s -> s.startsWith("事故复盘：")).anyMatch(s -> s.contains("坏的：配置有误"));
        assertThat(packs.fallback()).isEmpty(); // no 通用长文 in this company
    }

    @Test
    void standardsAreAppendedToTheGuideAndAMissingOneFailsLoudly() {
        String pack = "---\ngenre: g\nrequired_sections: [结论]\nstandards: [规范甲]\n---\n本文体的正文";
        GenrePacks ok = new GenrePacks(() -> List.of(skill("文体", pack), skill("规范甲", "规范甲的内容")));
        assertThat(ok.find("文体").orElseThrow().guide()).contains("本文体的正文", "规范：规范甲", "规范甲的内容");
        GenrePacks missing = new GenrePacks(() -> List.of(skill("文体", pack)));
        assertThatThrownBy(() -> missing.find("文体")).hasMessageContaining("字段 standards").hasMessageContaining("规范甲");
    }

    @Test
    void everyBuiltInPackLoadsAndNamesStandardsThatExist() {
        List<Skill> all = GenreSeeds.ALL.stream().map((SkillRequest r) -> skill(r.name(), r.content())).toList();
        GenrePacks packs = new GenrePacks(() -> all);
        for (String name : List.of(GenreSeeds.RESEARCH, GenreSeeds.DESIGN, GenreSeeds.WECHAT, GenreSeeds.GENERAL)) {
            GenrePacks.Loaded l = packs.find(name).orElseThrow();
            assertThat(l.pack().requiredSections()).isNotEmpty();
            assertThat(l.guide()).contains("规范：去 AI 味写作规范");
        }
        assertThat(packs.find(GenreSeeds.DESIGN).orElseThrow().guide()).contains("规范：技术画图规范", "规范：证据与引用规范");
        assertThat(packs.find(GenreSeeds.RESEARCH).orElseThrow().pack().investigates()).isTrue();
        assertThat(packs.fallback()).get().satisfies(l -> assertThat(l.pack().name()).isEqualTo("通用长文"));
        // The shared standards are not packs.
        assertThat(packs.describe()).hasSize(4);
    }
}
