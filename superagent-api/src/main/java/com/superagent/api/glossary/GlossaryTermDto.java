package com.superagent.api.glossary;

import java.time.Instant;
import java.util.List;

/** A term as the glossary page shows it; {@code projectId} null: the company's own. */
public record GlossaryTermDto(String id, String projectId, String term, List<String> aliases, String category,
                              String source, String sourceRef, String createdBy, Instant updatedAt) {
}
