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

    /** A blank {@code folderId} takes the file out of its folder. */
    public record Move(String folderId) {
    }

    public record SaveFolder(String name, String parentId) {
    }

    /** A Markdown file a person writes in the page; {@code folderId} (optional) is where it goes. */
    public record CreateMarkdown(String name, String content, String folderId) {
    }

    public record EditContent(String content) {
    }
}
