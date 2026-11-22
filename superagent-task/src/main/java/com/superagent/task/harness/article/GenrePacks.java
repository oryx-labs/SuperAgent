package com.superagent.task.harness.article;

import com.superagent.core.skill.Skill;
import com.superagent.skills.SkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The company's genre packs: its enabled company skills that open with a pack configuration block. A company adds a
 * genre by adding such a skill; nothing here knows the genres by name except the fallback.
 */
@Component
public class GenrePacks {

    public static final String FALLBACK = "通用长文";
    private static final int GUIDE_CHARS = 30_000;

    /** A pack with the text the author is given: its own and its standards'. */
    public record Loaded(GenrePack pack, String guide) {
    }

    private final Supplier<List<Skill>> skills;

    @Autowired
    public GenrePacks(SkillService skills) {
        this(skills::companySkills);
    }

    GenrePacks(Supplier<List<Skill>> skills) {
        this.skills = skills;
    }

    /** No company skills: the writing flow then keeps its original behaviour. */
    public static GenrePacks none() {
        return new GenrePacks(List::of);
    }

    /** Every pack that loads, for the agent listing the options; one with a wrong block is reported, not hidden. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Skill s : skills.get()) {
            if (GenrePack.isPack(s.content())) {
                try {
                    GenrePack p = GenrePack.parse(s.name(), s.content());
                    out.add(p.name() + "：" + (s.description() == null ? "" : s.description()) + "（" + (p.investigates() ? "读仓库和资料做调研" : "整理给定资料")
                            + (p.needsRepo() ? "，需要仓库" : "") + "）");
                } catch (GenrePackException e) {
                    out.add(s.name() + "：配置有误，暂不能用（" + e.getMessage() + "）");
                }
            }
        }
        return out;
    }

    /** The pack called {@code name} (its skill's name, or its genre id); empty when there is none. A wrong block throws. */
    public Optional<Loaded> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String want = name.strip().toLowerCase(Locale.ROOT);
        List<Skill> all = skills.get();
        for (Skill s : all) {
            if (s.name().strip().toLowerCase(Locale.ROOT).equals(want) && GenrePack.isPack(s.content())) {
                return Optional.of(load(s, all));
            }
        }
        for (Skill s : all) {
            if (GenrePack.isPack(s.content())) {
                try {
                    if (GenrePack.parse(s.name(), s.content()).genre().toLowerCase(Locale.ROOT).equals(want)) {
                        return Optional.of(load(s, all));
                    }
                } catch (GenrePackException e) {
                    // another pack's mistake: reported when that pack is asked for
                }
            }
        }
        return Optional.empty();
    }

    /** The company's plain-writing word list (the same one general checks use); a wrong block throws. */
    public com.superagent.core.writing.StyleLexicon lexicon() {
        return com.superagent.core.writing.StyleLexicon.of(skills.get(), null);
    }

    /** The fallback when no genre was chosen or it doesn't exist; empty when the company has no packs at all. */
    public Optional<Loaded> fallback() {
        return find(FALLBACK);
    }

    private static Loaded load(Skill s, List<Skill> all) {
        GenrePack pack = GenrePack.parse(s.name(), s.content());
        StringBuilder guide = new StringBuilder(GenrePack.body(s.content()));
        for (String standard : pack.standards()) {
            Skill std = all.stream().filter(x -> x.name().strip().equalsIgnoreCase(standard)).findFirst()
                    .orElseThrow(() -> new GenrePackException(s.name(), "standards", "找不到规范 skill「" + standard + "」（要是公司的、已启用的 skill）"));
            guide.append("\n\n## 规范：").append(std.name()).append("\n").append(GenrePack.body(std.content()));
        }
        String text = guide.toString();
        return new Loaded(pack, text.length() > GUIDE_CHARS ? text.substring(0, GUIDE_CHARS) : text);
    }
}
