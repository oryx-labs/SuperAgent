package com.superagent.api.task;

/**
 * One phase of a task's flow for the detail view. {@code status} is a lowercase
 * {@code TaskPhaseStatus}; {@code content} carries the step's document (PRD markdown)
 * and {@code evidence} its verification output, each null until produced.
 */
public record TaskPhaseDto(
        String key,
        String name,
        String status,
        String content,
        String evidence) {
}
