package com.superagent.core.skill;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for skills and for which skills an agent or task uses. A binding is the full,
 * ordered list of skill ids for one owner ({@code "agent"} or {@code "task"} + id); saving
 * replaces it. No binding at all is distinct from an empty one.
 */
public interface SkillStore {

    String AGENT = "agent";
    String TASK = "task";

    /** All skills, including soft-deleted ones; callers filter. */
    List<Skill> list();

    Optional<Skill> find(String id);

    /** Insert or replace by id. */
    void save(Skill skill);

    Optional<List<String>> binding(String ownerType, String ownerId);

    void bind(String ownerType, String ownerId, List<String> skillIds);
}
