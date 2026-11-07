package com.superagent.core.skill;

import java.util.List;
import java.util.Optional;

/** Skill deletions proposed in conversations; see {@link SkillDeletion}. */
public interface SkillDeletionStore {

    void save(SkillDeletion deletion);

    Optional<SkillDeletion> find(String id);

    /** The ones proposed in this agent's conversation, newest first. */
    List<SkillDeletion> forAgent(String agentId);
}
