package com.superagent.core.file;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Projects' shared folder trees and which files are mounted in them. A file is stored once; mounting it in a project
 * (at most once per project, in any number of projects) only records where it shows up. Unmounting never deletes it.
 */
public interface ProjectFolderStore {

    /** Inserts or replaces by id. */
    void save(ProjectFolder folder);

    /** A live folder. */
    Optional<ProjectFolder> find(String id);

    /** The project's live folders. */
    List<ProjectFolder> list(String projectId);

    /** fileId → folderId for the project; "" = its root. */
    Map<String, String> placements(String projectId);

    /** Projects the file is mounted in. */
    List<String> projectsOfFile(String fileId);

    /** Mounts the file in the project (moving it if already there). {@code by}: user, agent, auto or migrate. */
    void place(String projectId, String fileId, String folderId, String by);

    void unplace(String projectId, String fileId);
}
