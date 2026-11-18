package com.superagent.api.project;

/** A code repository a project works on; {@code delivery} is merge or pr. */
public record ProjectRepoDto(String id, String name, String location, String defaultBranch, String delivery, String purpose) {
}
