package com.superagent.core.glossary;

import java.time.Instant;
import java.util.List;

/**
 * How a name, term or abbreviation is written, and what it is often misheard as. {@code projectId} null: the company's
 * own list; else that project's. {@code source}: {@code manual}, {@code document} (taken from a task's materials,
 * {@code sourceRef} naming the file and page) or {@code correction} (a user's fix of a subtitle).
 */
public record GlossaryTerm(String id, String projectId, String term, List<String> aliases, String category,
                           String source, String sourceRef, String createdBy, Instant createdAt, Instant updatedAt) {

    public static final String MANUAL = "manual", DOCUMENT = "document", CORRECTION = "correction";
}
