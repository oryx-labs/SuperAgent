package com.superagent.api.superagent;

import java.time.Instant;

/** One step in a piece of SuperAgent work: assigned | needs_confirmation | replied | done | error | stopped | deleted. */
public record SuperAgentWorkEventDto(String kind, String detail, Instant at) {
}
