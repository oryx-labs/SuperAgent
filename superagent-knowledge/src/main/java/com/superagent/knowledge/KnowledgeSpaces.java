package com.superagent.knowledge;

import com.superagent.api.knowledge.KnowledgeSpaceDto;
import com.superagent.core.org.OrgStore;
import com.superagent.core.org.TeamPaths;
import com.superagent.core.user.UserStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Who may use which knowledge base (skills use the personal and company rules). Three layers:
 * <ul>
 *   <li><b>personal</b>: the user's own; only they read and write it;</li>
 *   <li><b>team</b>: members of a team read and write it; members of a sub-team may read (not write) the
 *       teams above theirs, so department-wide material reaches everyone in it — not the other way round;</li>
 *   <li><b>company</b>: everyone reads; only administrators write.</li>
 * </ul>
 * A space the user can't read reads as "not found"; one they may only read refuses writes.
 */
@Service
public class KnowledgeSpaces {

    private final OrgStore org;
    private final UserStore users;

    public KnowledgeSpaces(OrgStore org, UserStore users) {
        this.org = org;
        this.users = users;
    }

    /** The spaces the user can open, in sidebar order: personal, their teams (and the teams above), company. */
    public List<KnowledgeSpaceDto> visible(String user) {
        List<KnowledgeSpaceDto> out = new ArrayList<>();
        out.add(new KnowledgeSpaceDto("personal", "personal", null, true));
        Set<String> own = Set.copyOf(org.teams(user));
        for (String team : readableTeams(own)) {
            out.add(new KnowledgeSpaceDto("team:" + team, "team", team, own.contains(team)));
        }
        out.add(new KnowledgeSpaceDto("company", "company", null, isAdmin(user)));
        return out;
    }

    /**
     * The space named by {@code id} ("personal", "team:<path>", "company"; blank = personal), if the user may
     * read it — and, when {@code write}, write it.
     */
    public KnowledgeSpace resolve(String user, String id, boolean write) {
        if (id == null || id.isBlank() || "personal".equals(id)) {
            return KnowledgeSpace.personal(user);
        }
        if ("company".equals(id)) {
            if (write && !isAdmin(user)) {
                throw new KnowledgeReadOnlyException("Only administrators can change the company knowledge base");
            }
            return KnowledgeSpace.company();
        }
        if (id.startsWith("team:")) {
            String team;
            try {
                team = TeamPaths.normalize(id.substring(5));
            } catch (IllegalArgumentException e) {
                throw new KnowledgeNotFoundException(id);
            }
            Set<String> own = Set.copyOf(org.teams(user));
            if (!readableTeams(own).contains(team)) {
                throw new KnowledgeNotFoundException(id); // not your team: it doesn't exist for you
            }
            if (write && !own.contains(team)) {
                throw new KnowledgeReadOnlyException("Only members of " + team + " can change its knowledge base");
            }
            return KnowledgeSpace.team(team);
        }
        throw new KnowledgeNotFoundException(id);
    }

    /** The same rules for what else lives in a space (skills); a predicate so a long list costs one lookup. */
    public Predicate<KnowledgeSpace> readableBy(String user) {
        Set<String> teams = readableTeams(Set.copyOf(org.teams(user)));
        return space -> switch (space.kind()) {
            case PERSONAL -> space.key().equals(user);
            case TEAM -> teams.contains(space.key());
            case COMPANY -> true;
        };
    }

    public Predicate<KnowledgeSpace> writableBy(String user) {
        Set<String> teams = Set.copyOf(org.teams(user));
        boolean admin = isAdmin(user);
        return space -> switch (space.kind()) {
            case PERSONAL -> space.key().equals(user);
            case TEAM -> teams.contains(space.key());
            case COMPANY -> admin;
        };
    }

    private static Set<String> readableTeams(Set<String> own) {
        Set<String> out = new LinkedHashSet<>();
        own.stream().sorted().forEach(t -> out.addAll(TeamPaths.withAncestors(t)));
        return out;
    }

    public boolean isAdmin(String user) {
        return users.findByUsername(user).map(u -> u.roles().contains("ADMIN")).orElse(false);
    }
}
