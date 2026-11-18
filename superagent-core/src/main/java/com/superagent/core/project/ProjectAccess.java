package com.superagent.core.project;

import java.util.List;
import java.util.Optional;

/**
 * What other modules need from projects, without depending on the project module: who can use an agent through a
 * project, whether an agent's project is archived, and recording where new tasks and files belong. Absent (null)
 * in tests and wherever projects aren't wired, which means "no projects".
 */
public interface ProjectAccess {

    /** Agents the user can use because they are a member of the agents' project. */
    List<String> agentsThroughProjects(String user);

    /** Whether the user is a member of the project this agent is in. */
    boolean sharesProject(String agentId, String user);

    /** The project the agent is in, if any. */
    Optional<String> projectOfAgent(String agentId);
    /** Puts the user's own agent into a project they are in (as the project page does); refused for an archived one. */
    void placeAgent(String agentId, String user, String projectId);

    /** Whether the agent is in an archived project (then it is read-only). */
    boolean archived(String agentId);

    /** Whether the user can see this task or file because it was made in one of their projects. */
    boolean visibleThroughProject(ProjectItemKind kind, String itemId, String user);

    /** Ids of the project's tasks or files, for a user who is a member of it (empty for anyone else). */
    List<String> itemsInProject(String projectId, ProjectItemKind kind, String user);

    /** Records a new task in the project of the agent that set it off (no-op when the agent is in none). */
    void recordTask(String taskId, String agentId);

    /** Records a new file in its task's project, else its agent's (no-op when neither is in one). */
    void recordFile(String fileId, String agentId, String taskId);

    /** Projects the user is an active member of. */
    List<Project> projectsOf(String user);

    /** Whether the user is an active member of the project. */
    boolean member(String projectId, String user);

    /** Whether the project is archived (then everything in it is read-only). */
    boolean projectArchived(String projectId);

    /** Throws unless the user is a member of the project and it isn't archived (archived projects are read-only). */
    void requireWritable(String projectId, String user);

    /** Forgets that the file was made in this project, once it's taken out of the project's folders. */
    void forgetFile(String projectId, String fileId);

    /** The project a task was made in. */
    Optional<String> projectOfTask(String taskId);

    /** Repositories of the project the agent is in (empty when it's in none). */
    List<ProjectRepo> reposOfAgent(String agentId);

    /**
     * The project's shared context (brief, index of its files and repositories, memory) for a run of this agent, or
     * of this task (its own project first): capped in length, the rest by title only. Empty outside any project.
     */
    Optional<String> context(String agentId, String taskId);
}
