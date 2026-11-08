package com.superagent.core.project;

/** A project is worked in while active; archived, its agents are read-only and everything it produced is kept. */
public enum ProjectStatus {
    ACTIVE,
    ARCHIVED
}
