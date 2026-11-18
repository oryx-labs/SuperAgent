package com.superagent.core.project;

import java.util.List;
import java.util.Optional;

/** A project's repositories, brief and memory: what its agents share. */
public interface ProjectContextStore {

    /** The project's repositories in their order. */
    List<ProjectRepo> repos(String projectId);

    /** Inserts or replaces by id. */
    void saveRepo(ProjectRepo repo);

    void deleteRepo(String projectId, String repoId);

    Optional<ProjectBrief> brief(String projectId);

    /** Inserts or replaces by project. */
    void saveBrief(ProjectBrief brief);

    /** Live entries, newest first. */
    List<ProjectMemory> memories(String projectId);

    /** A live entry. */
    Optional<ProjectMemory> memory(String id);

    /** Inserts or replaces by id; a deleted entry is kept with its flag set. */
    void saveMemory(ProjectMemory memory);
}
