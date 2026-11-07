package com.superagent.core.file;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence SPI for folders and where each person keeps each file. Placement is per person, because several
 * people can see one file and each files it their own way.
 */
public interface FileFolderStore {

    /** Inserts or replaces by id; a deleted folder is kept as a row with its flag set. */
    void save(FileFolder folder);

    /** A live folder. */
    Optional<FileFolder> find(String id);

    /** The person's live folders. */
    List<FileFolder> list(String owner);

    /** Everyone with at least one live folder. */
    List<String> owners();

    /** fileId → folderId for this person; "" means it was considered and stays unfiled (so it isn't asked about again). */
    Map<String, String> placements(String owner);

    /** Inserts or replaces. {@code by} says who decided: user, agent or auto. */
    void place(String owner, String fileId, String folderId, String by);
}
