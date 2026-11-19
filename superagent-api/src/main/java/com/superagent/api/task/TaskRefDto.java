package com.superagent.api.task;

/** Another task, named: what a task waits for. {@code status} is lowercase. */
public record TaskRefDto(String id, String title, String status) {
}
