package com.superagent.core.project;

/** What can belong to a project. An agent belongs to at most one; tasks and files keep the project they were made in. */
public enum ProjectItemKind {
    AGENT,
    TASK,
    FILE
}
