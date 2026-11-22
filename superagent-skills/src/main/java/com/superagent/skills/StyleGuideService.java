package com.superagent.skills;

import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.skill.Skill;
import com.superagent.core.writing.StyleGuideSource;
import com.superagent.core.writing.StyleLexicon;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The company's document standard for system prompts and checks: the skill picked in the settings (default: the
 * plain-writing standard) boiled down to {@link #MAX_CHARS} characters, and its word list. Per company, switchable.
 */
@Service
public class StyleGuideService implements StyleGuideSource {

    private static final Pattern BLOCK = Pattern.compile("(?s)\\n*(?:## 平台词表[^\\n]*\\n+)?```lexicon[ \\t]*\\R.*?```\\n*");
    private static final Pattern DROPPABLE = Pattern.compile("(?m)^## (好和坏|写完自查|语法提醒).*$");
    private static final int EXTRA_CHARS = 350;

    private final SkillService skills;
    private final SystemSettingStore settings;

    public StyleGuideService(SkillService skills, SystemSettingStore settings) {
        this.skills = skills;
        this.settings = settings;
    }

    @Override
    public Optional<String> compactGuide(boolean technical) {
        Map<String, String> all = settings.all();
        if ("false".equalsIgnoreCase(all.get(StyleGuideSource.ON_KEY))) {
            return Optional.empty();
        }
        List<Skill> company = skills.companySkills();
        Skill main = StyleLexicon.pick(company, all.get(StyleGuideSource.SKILL_KEY));
        if (main == null) {
            return Optional.empty();
        }
        String head = "## 文档写作规范（所有文档都遵守；来自公司的「" + main.name() + "」）\n";
        StringBuilder extras = new StringBuilder();
        if (technical) {
            for (String name : List.of(GenreSeeds.EVIDENCE, GenreSeeds.DIAGRAMS)) {
                Skill s = StyleLexicon.pick(company, name);
                if (s != null && !s.id().equals(main.id())) {
                    extras.append("\n\n### 技术文档：").append(s.name()).append("\n").append(condense(s.content(), EXTRA_CHARS));
                }
            }
        }
        int budget = MAX_CHARS - head.length() - extras.length();
        String out = head + condense(main.content(), budget) + extras;
        return Optional.of(out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) : out);
    }

    @Override
    public StyleLexicon lexicon() {
        return StyleLexicon.of(skills.companySkills(), settings.all().get(StyleGuideSource.SKILL_KEY));
    }

    /** The skill's text without its word list and examples, cut at a line to fit {@code budget}. */
    static String condense(String content, int budget) {
        String text = BLOCK.matcher(content == null ? "" : content).replaceAll("\n").strip();
        if (text.length() > budget) {
            text = dropSections(text);
        }
        if (text.length() <= budget) {
            return text;
        }
        int cut = text.lastIndexOf('\n', budget);
        return text.substring(0, cut > budget / 2 ? cut : budget).stripTrailing();
    }

    private static String dropSections(String text) {
        StringBuilder out = new StringBuilder();
        boolean skip = false;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("## ")) {
                skip = DROPPABLE.matcher(line).matches();
            }
            if (!skip) {
                out.append(line).append('\n');
            }
        }
        return out.toString().strip();
    }
}
