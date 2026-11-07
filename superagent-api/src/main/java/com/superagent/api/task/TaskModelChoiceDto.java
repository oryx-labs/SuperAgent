package com.superagent.api.task;

import java.time.Instant;

/**
 * One decision about which model ran a task, for its "model choice" tab. {@code by}: judge | user | fallback |
 * escalation; {@code candidates}: the models it could have run on, one per line ("id | tier | $in / $out | …").
 */
public record TaskModelChoiceDto(int seq, String by, String difficulty, String tier, String model, String cli,
                                 String judge, String candidates, String reasoning, Instant createdAt) {
}
