package com.superagent.api.file;

/** Request bodies for the work-file endpoints. */
public final class WorkFileRequests {

    private WorkFileRequests() {
    }

    public record Rename(String name) {
    }

    /** The file's tags as a person sets them; these win over the agent's and the tagger's from then on. */
    public record Tags(java.util.List<String> tags) {
    }
}
