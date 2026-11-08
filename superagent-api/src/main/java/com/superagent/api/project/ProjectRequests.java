package com.superagent.api.project;

import java.util.List;

/** Request bodies for projects. */
public final class ProjectRequests {

    public record Save(String name, String description) {
    }

    public record Members(List<String> usernames) {
    }

    /** Moves an agent into a project, or out of any ({@code projectId} null). */
    public record AgentProject(String projectId) {
    }

    private ProjectRequests() {
    }
}
