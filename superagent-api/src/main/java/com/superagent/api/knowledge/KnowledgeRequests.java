package com.superagent.api.knowledge;

/** Request bodies for knowledge-base mutations; every path is relative to the caller's root. */
public final class KnowledgeRequests {

    /** Create the folder at {@code path}; its parent must exist. */
    public record CreateFolder(String path) {
    }

    /** Rename the entry at {@code path} to {@code newName} in the same folder. */
    public record Rename(String path, String newName) {
    }

    /** Move the entry at {@code path} into the folder {@code targetDir} ("" = root). */
    public record Move(String path, String targetDir) {
    }

    /** Save a task artifact into the folder {@code folder} ("" = root; created if missing). */
    public record SaveArtifact(String folder) {
    }

    private KnowledgeRequests() {
    }
}
