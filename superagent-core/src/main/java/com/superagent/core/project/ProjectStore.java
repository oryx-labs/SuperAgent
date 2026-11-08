package com.superagent.core.project;

import java.util.List;
import java.util.Optional;

public interface ProjectStore {

    /** Insert or replace by id. */
    void save(Project project);

    Optional<Project> find(String id);

    List<Project> list();

    /** Insert or replace by project + username. */
    void saveMember(ProjectMember member);

    /** Active members of a project. */
    List<ProjectMember> members(String projectId);

    /** Ids of projects the user is an active member of. */
    List<String> projectsOf(String username);
}
