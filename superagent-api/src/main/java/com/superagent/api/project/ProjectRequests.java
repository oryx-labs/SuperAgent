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

    /** A repository; {@code delivery} merge or pr (blank = merge). */
    public record Repo(String name, String location, String defaultBranch, String delivery, String purpose) {
    }

    public record Brief(String goal, String background, String norms, String glossary) {
    }

    public record Memory(String title, String content) {
    }

    private ProjectRequests() {
    }
}
