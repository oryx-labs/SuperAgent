package com.superagent.core.glossary;

import java.util.List;
import java.util.Optional;

/** The current company's terms (every call is scoped to it). */
public interface GlossaryStore {

    /** {@code projectId} null: the company's own list. */
    List<GlossaryTerm> list(String projectId);

    Optional<GlossaryTerm> find(String id);

    void save(GlossaryTerm term);

    void delete(String id);
}
