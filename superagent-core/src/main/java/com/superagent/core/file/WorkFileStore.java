package com.superagent.core.file;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence SPI for work files. Besides the files themselves it keeps which chat replies produced each one
 * (a file revised in a later reply is linked from both), so a conversation shows its file cards after reload.
 */
public interface WorkFileStore {

    /** Inserts or replaces by id. */
    void save(WorkFile file);

    Optional<WorkFile> find(String id);

    /**
     * Live files the owner gave the instruction for, or made by one of {@code agentIds}; newest first. A null
     * owner lists only the agents' files.
     */
    List<WorkFile> listFor(String owner, Collection<String> agentIds);

    /** Live files nobody has tagged yet (oldest first), for the tagger. */
    List<WorkFile> untagged(int limit);

    /** A task's automatically captured delivery file, if any (deleted ones included, so it isn't re-created). */
    Optional<WorkFile> delivery(String taskId);

    /** Records that the chat reply {@code messageId} produced (or revised) {@code fileId}. Idempotent. */
    void link(String fileId, String sessionId, String messageId);

    /** fileId → the chat replies that produced it. */
    Map<String, List<String>> messages(Collection<String> fileIds);
}
