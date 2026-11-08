package com.superagent.core.project;

import java.util.List;
import java.util.Optional;

/** Which project an agent, task or file is in. Recorded when the item is made (tasks, files) or moved (agents). */
public interface ProjectItemStore {

    /** Puts the item in the project, replacing any project it was in. */
    void assign(ProjectItemKind kind, String itemId, String projectId);

    /** Takes the item out of whatever project it was in. */
    void unassign(ProjectItemKind kind, String itemId);

    Optional<String> projectOf(ProjectItemKind kind, String itemId);

    /** Ids of the project's items of this kind. */
    List<String> items(ProjectItemKind kind, String projectId);
}
