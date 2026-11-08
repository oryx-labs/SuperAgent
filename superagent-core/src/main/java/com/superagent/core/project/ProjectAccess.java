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
}
