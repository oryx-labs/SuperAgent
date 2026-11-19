package com.superagent.glossary;

import com.superagent.api.glossary.GlossaryTermDto;
import com.superagent.api.glossary.GlossaryTermRequest;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.error.NotFoundException;
import com.superagent.core.glossary.GlossaryStore;
import com.superagent.core.glossary.GlossaryTerm;
import com.superagent.core.media.TextTokens;
import com.superagent.core.project.ProjectAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The company's glossary and each project's: what flows (subtitles) spell names and terms by, and what they learn
 * into it (terms from a task's materials, a user's fixes). The same term is kept once a list, by its tokens (case,
 * spacing and punctuation aside); a project's entry stands over the company's, with both sets of mishearings.
 */
@Service
public class GlossaryService {

    static final int MAX_TERM = 60;

    private final GlossaryStore store;
    private ProjectAccess projects; // null where projects aren't wired: only the company's list

    public GlossaryService(GlossaryStore store) {
        this.store = store;
    }

    @Autowired(required = false)
    void setProjects(ProjectAccess projects) {
        this.projects = projects;
    }

    /** A term found in a document: {@code ref} names the file and page. */
    public record Found(String term, String category, String ref) {
    }

    // --- what people do on the page ---

    public List<GlossaryTermDto> list(String projectId, String user) {
        if (projectId != null) {
            requireMember(projectId, user);
        }
        return store.list(projectId).stream().map(GlossaryService::dto).toList();
    }

    public GlossaryTermDto create(String projectId, GlossaryTermRequest r, String user, boolean admin) {
        requireWrite(projectId, user, admin);
        String term = clean(r.term());
        if (term == null) {
            throw new IllegalArgumentException("术语不能为空，最长 " + MAX_TERM + " 个字");
        }
        return dto(upsert(projectId, term, aliases(r.aliases()), r.category(), GlossaryTerm.MANUAL, null, user, true));
    }

    public GlossaryTermDto update(String id, GlossaryTermRequest r, String user, boolean admin) {
        GlossaryTerm t = store.find(id).orElseThrow(() -> new NotFoundException("Glossary term not found: " + id));
        requireWrite(t.projectId(), user, admin);
        String term = r.term() == null ? t.term() : clean(r.term());
        if (term == null) {
            throw new IllegalArgumentException("术语不能为空，最长 " + MAX_TERM + " 个字");
        }
        GlossaryTerm saved = new GlossaryTerm(t.id(), t.projectId(), term, r.aliases() == null ? t.aliases() : aliases(r.aliases()),
                r.category() == null ? t.category() : r.category(), t.source(), t.sourceRef(), t.createdBy(), t.createdAt(),
                Instant.now());
        store.save(saved);
        return dto(saved);
    }

    public void delete(String id, String user, boolean admin) {
        GlossaryTerm t = store.find(id).orElseThrow(() -> new NotFoundException("Glossary term not found: " + id));
        requireWrite(t.projectId(), user, admin);
        store.delete(id);
    }

    // --- what flows use and teach it ---

    /** The company's terms with the project's over them (the project's spelling, both lists' mishearings). */
    public List<GlossaryTerm> merged(String projectId) {
        Map<String, GlossaryTerm> byKey = new LinkedHashMap<>();
        store.list(null).forEach(t -> byKey.put(TextTokens.key(t.term()), t));
        if (projectId != null) {
            for (GlossaryTerm t : store.list(projectId)) {
                GlossaryTerm company = byKey.get(TextTokens.key(t.term()));
                byKey.put(TextTokens.key(t.term()), company == null ? t : withAliases(t, union(t.aliases(), company.aliases(), t.term())));
            }
        }
        return List.copyOf(byKey.values());
    }

    /** Terms found in a task's materials, into the project's list (the company's outside a project); how many were new. */
    public int addFound(String projectId, List<Found> found, String by) {
        Map<String, GlossaryTerm> known = new LinkedHashMap<>();
        merged(projectId).forEach(t -> known.put(TextTokens.key(t.term()), t));
        int added = 0;
        for (Found f : found) {
            String term = clean(f.term());
            if (term == null || known.containsKey(TextTokens.key(term))) {
                continue;
            }
            known.put(TextTokens.key(term), upsert(projectId, term, List.of(), f.category(), GlossaryTerm.DOCUMENT, f.ref(), by, false));
            added++;
        }
        return added;
    }

    /**
     * A user corrected {@code heard} to {@code corrected}: the part that changed is a term spelled that way, and what was
     * heard instead a mishearing of it. Nothing when nothing changed or the change is no term (longer than a phrase).
     */
    public Optional<GlossaryTerm> learn(String projectId, String heard, String corrected, String by) {
        List<TextTokens.Token> h = TextTokens.of(heard), c = TextTokens.of(corrected);
        int p = 0;
        while (p < h.size() && p < c.size() && h.get(p).surface().equals(c.get(p).surface())) {
            p++;
        }
        int hs = h.size(), cs = c.size();
        while (hs > p && cs > p && h.get(hs - 1).surface().equals(c.get(cs - 1).surface())) {
            hs--;
            cs--;
        }
        String term = clean(TextTokens.join(c.subList(p, cs)));
        if (term == null) {
            return Optional.empty();
        }
        String wrong = TextTokens.join(h.subList(p, hs));
        List<String> aliases = wrong.isBlank() || TextTokens.key(wrong).equals(TextTokens.key(term)) ? List.of() : List.of(wrong);
        return Optional.of(upsert(projectId, term, aliases, null, GlossaryTerm.CORRECTION, null, by, false));
    }

    // ---

    /** Into {@code projectId}'s list: the same term there gains the aliases (and, by a person, the spelling). */
    private GlossaryTerm upsert(String projectId, String term, List<String> aliases, String category, String source,
                                String ref, String by, boolean respell) {
        Instant now = Instant.now();
        String key = TextTokens.key(term);
        Optional<GlossaryTerm> same = store.list(projectId).stream().filter(t -> TextTokens.key(t.term()).equals(key)).findFirst();
        GlossaryTerm saved = same.map(t -> new GlossaryTerm(t.id(), projectId, respell || GlossaryTerm.CORRECTION.equals(source)
                        ? term : t.term(), union(t.aliases(), aliases, term), category == null ? t.category() : category,
                        t.source(), t.sourceRef(), t.createdBy(), t.createdAt(), now))
                .orElseGet(() -> new GlossaryTerm(UUID.randomUUID().toString(), projectId, term, union(List.of(), aliases, term),
                        category, source, ref, by, now, now));
        store.save(saved);
        return saved;
    }

    private static GlossaryTerm withAliases(GlossaryTerm t, List<String> aliases) {
        return new GlossaryTerm(t.id(), t.projectId(), t.term(), aliases, t.category(), t.source(), t.sourceRef(), t.createdBy(),
                t.createdAt(), t.updatedAt());
    }

    private static List<String> union(List<String> a, List<String> b, String term) {
        Map<String, String> out = new LinkedHashMap<>();
        String self = TextTokens.key(term);
        for (List<String> list : List.of(a, b)) {
            for (String s : list) {
                String c = clean(s);
                if (c != null && !TextTokens.key(c).equals(self)) {
                    out.putIfAbsent(TextTokens.key(c), c);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private static List<String> aliases(List<String> given) {
        return given == null ? List.of() : union(List.of(), given, "\u0000");
    }

    /** Stripped, on one line; null when empty or too long to be a term. */
    static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.isEmpty() || t.length() > MAX_TERM || TextTokens.of(t).isEmpty() ? null : t;
    }

    private void requireMember(String projectId, String user) {
        if (projects == null || !projects.member(projectId, user)) {
            throw new ForbiddenException("不是这个项目的成员");
        }
    }

    private void requireWrite(String projectId, String user, boolean admin) {
        if (projectId == null) {
            if (!admin) {
                throw new ForbiddenException("公司术语表只有管理员能改");
            }
            return;
        }
        if (projects == null) {
            throw new ForbiddenException("不是这个项目的成员");
        }
        projects.requireWritable(projectId, user);
    }

    static GlossaryTermDto dto(GlossaryTerm t) {
        return new GlossaryTermDto(t.id(), t.projectId(), t.term(), t.aliases(), t.category(), t.source(), t.sourceRef(),
                t.createdBy(), t.updatedAt());
    }
}
