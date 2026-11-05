package com.superagent.knowledge.skill;

import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillRequest;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.skill.Skill;
import com.superagent.core.skill.SkillStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillServiceTest {

    private static final class InMemorySkills implements SkillStore {
        final Map<String, Skill> rows = new LinkedHashMap<>();
        final Map<String, List<String>> bindings = new HashMap<>();
        @Override public List<Skill> list() { return new ArrayList<>(rows.values()); }
        @Override public Optional<Skill> find(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public void save(Skill s) { rows.put(s.id(), s); }
        @Override public Optional<List<String>> binding(String type, String id) { return Optional.ofNullable(bindings.get(type + id)); }
        @Override public void bind(String type, String id, List<String> ids) { bindings.put(type + id, List.copyOf(ids)); }
    }

    private static final class InMemorySettings implements SystemSettingStore {
        final Map<String, String> rows = new LinkedHashMap<>();
        @Override public Map<String, String> all() { return new LinkedHashMap<>(rows); }
        @Override public void put(String key, String value) { rows.put(key, value); }
    }

    @TempDir
    Path tmp;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    @Test
    void seedsOnceThenSharedEditingWithCreatorOnlyDelete() {
        InMemorySkills store = new InMemorySkills();
        InMemorySettings settings = new InMemorySettings();
        SkillService svc = new SkillService(store, settings, tmp.toString());
        assertThat(svc.list()).hasSize(SkillSeeds.ALL.size());
        svc.list().forEach(s -> svc.delete(s.id(), "x", true));
        assertThat(new SkillService(store, settings, tmp.toString()).list()).isEmpty(); // not re-seeded

        SkillDto s = svc.create(new SkillRequest("写作", "写文章时", "写作", "短句。", null), "ada");
        assertThat(svc.update(s.id(), new SkillRequest(null, null, null, "更短的句子。", null), "bob").content())
                .isEqualTo("更短的句子。");
        assertThatThrownBy(() -> svc.delete(s.id(), "bob", false)).isInstanceOf(SkillForbiddenException.class);
        svc.delete(s.id(), "ada", false);
        assertThat(svc.list()).isEmpty();
    }

    @Test
    void agentTasksUseTheAgentsSkillsAndInstallWritesFilesPlusAnIndex() throws Exception {
        SkillService svc = new SkillService(new InMemorySkills(), new InMemorySettings(), tmp.resolve("skills").toString());
        String writing = svc.create(new SkillRequest("写作", "写文章时", null, "用短句。![例](images/x.png)", true), "ada").id();
        String design = svc.create(new SkillRequest("画图", "画图时", null, "三种颜色。", true), "ada").id();
        String stored = svc.addImage(writing, "x.png", PNG, "ada");
        assertThat(stored).isEqualTo("x.png");
        assertThatThrownBy(() -> svc.addImage(writing, "evil.svg", "<svg/>".getBytes(), "ada"))
                .isInstanceOf(IllegalArgumentException.class);

        svc.setAgentSkills("agent-1", List.of(writing));
        svc.bindTaskToAgent("task-1", "agent-1");
        assertThat(svc.forTask("task-1")).extracting(Skill::id).containsExactly(writing);
        assertThat(svc.forTask("page-task")).extracting(Skill::id).contains(writing, design); // all, incl. seeds

        Path ws = Files.createDirectories(tmp.resolve("ws"));
        String index = svc.install(ws.toString(), svc.forTask("task-1"), true);
        Path skillMd = ws.resolve(".superagent/skills/" + writing + "/SKILL.md");
        assertThat(index).contains("写作 — 写文章时").contains(skillMd.toString()).contains("skill_create");
        assertThat(Files.readString(skillMd)).contains("用短句。");
        assertThat(ws.resolve(".superagent/skills/" + writing + "/images/x.png")).exists();

        // A later run with fewer skills doesn't leave the old ones behind.
        svc.install(ws.toString(), List.of(), false);
        assertThat(ws.resolve(".superagent/skills/" + writing)).doesNotExist();
    }
}
