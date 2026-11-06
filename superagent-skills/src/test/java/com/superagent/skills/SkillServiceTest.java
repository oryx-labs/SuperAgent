package com.superagent.skills;

import com.superagent.api.skill.SkillDto;
import com.superagent.api.skill.SkillRequest;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.skill.Skill;
import com.superagent.core.skill.SkillStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** Everyone exists; only "root" is an administrator. */
    private static final class Users implements UserStore {
        @Override public List<User> listUsers() { return List.of(); }
        @Override public Optional<User> findById(String id) { return Optional.empty(); }
        @Override public Optional<User> findByUsername(String name) {
            Instant now = Instant.now();
            return Optional.of(new User(name, name, name, "root".equals(name) ? Set.of("ADMIN") : Set.of("USER"), true,
                    null, null, null, UserSource.LOCAL, now, now));
        }
        @Override public Optional<User> findByPhone(String phone) { return Optional.empty(); }
        @Override public Optional<User> findByExternalId(String id) { return Optional.empty(); }
        @Override public User save(User u) { return u; }
        @Override public void delete(String id) { }
    }

    private SkillService service(InMemorySkills store, InMemorySettings settings, Path dir) {
        return new SkillService(store, settings, new Users(), dir.toString());
    }

    private static SkillRequest req(String name, String space) {
        return new SkillRequest(name, name + "时", null, name + "的规范。", true, space);
    }

    @Test
    void seedsOnceIntoTheCompanyLayerWhichOnlyAdminsChange() {
        InMemorySkills store = new InMemorySkills();
        InMemorySettings settings = new InMemorySettings();
        SkillService svc = service(store, settings, tmp.resolve("skills"));
        assertThat(svc.list("ada")).hasSize(SkillSeeds.ALL.size())
                .allSatisfy(s -> assertThat(s.space()).isEqualTo("company"))
                .allSatisfy(s -> assertThat(s.canWrite()).isFalse());
        String seed = svc.list("ada").get(0).id();
        assertThatThrownBy(() -> svc.delete(seed, "ada")).isInstanceOf(SkillForbiddenException.class);
        assertThatThrownBy(() -> svc.create(req("制度", "company"), "ada")).isInstanceOf(SkillForbiddenException.class);
        svc.list("root").forEach(s -> svc.delete(s.id(), "root"));
        assertThat(service(store, settings, tmp.resolve("skills")).list("root")).isEmpty(); // not re-seeded
    }

    @Test
    void personalSkillsAreTheOwnersAndTeamSkillsAreRetired() {
        InMemorySkills store = new InMemorySkills();
        InMemorySettings settings = new InMemorySettings();
        SkillService svc = service(store, settings, tmp.resolve("skills"));
        svc.list("root").forEach(s -> svc.delete(s.id(), "root"));
        String mine = svc.create(req("周报", null), "bob").id();
        String company = svc.create(req("制度", "company"), "root").id();

        assertThat(svc.list("bob")).extracting(SkillDto::id).containsExactly(mine, company);
        assertThat(svc.list("bob")).extracting(SkillDto::canWrite).containsExactly(true, false);
        assertThat(svc.list("dan")).extracting(SkillDto::id).containsExactly(company);
        assertThatThrownBy(() -> svc.get(mine, "dan")).isInstanceOf(SkillNotFoundException.class);
        assertThatThrownBy(() -> svc.create(req("x", "team:技术中心"), "bob")).isInstanceOf(IllegalArgumentException.class);

        // An agent's owner attaches what they can see.
        assertThatThrownBy(() -> svc.setAgentSkills("agent-d", List.of(mine), "dan")).isInstanceOf(SkillNotFoundException.class);
        svc.setAgentSkills("agent-b", List.of(mine, company), "bob");
        assertThat(svc.forAgent("agent-b", "bob")).extracting(Skill::id).containsExactly(mine, company);

        // A team skill from before becomes its creator's own on the next start.
        Instant now = Instant.now();
        store.save(new Skill("00000000-0000-0000-0000-000000000001", "接口", null, null, "x", "team:技术中心", true,
                false, "dan", "dan", now, now));
        service(store, settings, tmp.resolve("skills"));
        assertThat(store.rows.get("00000000-0000-0000-0000-000000000001").space()).isEqualTo("personal:dan");
    }

    @Test
    void agentTasksUseTheAgentsSkillsAndInstallWritesFilesPlusAnIndex() throws Exception {
        SkillService svc = service(new InMemorySkills(), new InMemorySettings(), tmp.resolve("skills"));
        String writing = svc.create(new SkillRequest("写作", "写文章时", null, "用短句。![例](images/x.png)", true, null), "ada").id();
        String design = svc.create(req("画图", null), "ada").id();
        String stored = svc.addImage(writing, "x.png", PNG, "ada");
        assertThat(stored).isEqualTo("x.png");
        assertThatThrownBy(() -> svc.addImage(writing, "evil.svg", "<svg/>".getBytes(), "ada"))
                .isInstanceOf(IllegalArgumentException.class);

        svc.setAgentSkills("agent-1", List.of(writing), "ada");
        svc.bindTaskToAgent("task-1", "agent-1");
        assertThat(svc.forTask("task-1", "ada")).extracting(Skill::id).containsExactly(writing);
        assertThat(svc.forTask("page-task", "ada")).extracting(Skill::id).contains(writing, design); // all, incl. seeds

        Path ws = Files.createDirectories(tmp.resolve("ws"));
        String index = svc.install(ws.toString(), svc.forTask("task-1", "ada"), true);
        Path skillMd = ws.resolve(".superagent/skills/" + writing + "/SKILL.md");
        assertThat(index).contains("写作 — 写文章时").contains(skillMd.toString()).contains("skill_create");
        assertThat(Files.readString(skillMd)).contains("用短句。");
        assertThat(ws.resolve(".superagent/skills/" + writing + "/images/x.png")).exists();

        // A later run with fewer skills doesn't leave the old ones behind.
        svc.install(ws.toString(), List.of(), false);
        assertThat(ws.resolve(".superagent/skills/" + writing)).doesNotExist();
    }
}
