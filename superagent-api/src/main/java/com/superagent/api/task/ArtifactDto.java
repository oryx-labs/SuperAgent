package com.superagent.api.task;

/** A file produced in a task's workspace, offered for download (e.g. output.md). */
public record ArtifactDto(String name, long size) {
}
