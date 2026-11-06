package com.superagent.api.file;

/** Request bodies for the work-file endpoints. */
public final class WorkFileRequests {

    private WorkFileRequests() {
    }

    public record Rename(String name) {
    }

    /** Copy the file into the caller's own knowledge base; blank folder = the root. */
    public record SaveToKnowledge(String folder) {
    }
}
