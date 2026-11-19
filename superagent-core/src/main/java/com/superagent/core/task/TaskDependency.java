package com.superagent.core.task;

import java.time.Instant;

/**
 * Task {@code taskId} starts only once {@code dependsOn} is done. {@code notifiedAt}: when the owner was told that
 * {@code dependsOn} failed or was stopped and this one is stuck (null = not yet), so it is said once.
 */
public record TaskDependency(String taskId, String dependsOn, Instant createdAt, Instant notifiedAt) {
}
