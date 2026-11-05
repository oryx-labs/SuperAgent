package com.superagent.knowledge;

import com.superagent.core.org.OrgStore;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A small org for space tests: bob in 技术中心/后端组, dan in 技术中心, root is the admin, ada in no team. */
public final class TestOrg {

    private TestOrg() {
    }

    public static KnowledgeSpaces spaces(KnowledgeService knowledge) {
        Org org = new Org();
        org.setTeams("bob", List.of("技术中心/后端组"));
        org.setTeams("dan", List.of("技术中心"));
        return new KnowledgeSpaces(org, new Users(), knowledge);
    }

    private static final class Org implements OrgStore {
        final Map<String, List<String>> teams = new HashMap<>();
        @Override public Optional<String> manager(String u) { return Optional.empty(); }
        @Override public Map<String, String> managers() { return Map.of(); }
        @Override public void setManager(String u, String m) { }
        @Override public List<String> teams(String u) { return teams.getOrDefault(u, List.of()); }
        @Override public Map<String, List<String>> memberships() { return teams; }
        @Override public void setTeams(String u, List<String> t) { teams.put(u, t); }
        @Override public void renameTeam(String from, String to) { }
    }

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
}
