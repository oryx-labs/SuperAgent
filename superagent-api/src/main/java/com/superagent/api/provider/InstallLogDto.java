package com.superagent.api.provider;

import java.time.Instant;
import java.util.List;

/**
 * The last install or upgrade of a CLI (or of the private Node.js): what it's doing now ({@code step}, null once
 * finished), its output so far (the last few hundred lines) and, when it failed, why.
 */
public record InstallLogDto(String action, String step, Instant startedAt, Instant finishedAt, boolean running,
                            String error, List<String> lines) {
}
