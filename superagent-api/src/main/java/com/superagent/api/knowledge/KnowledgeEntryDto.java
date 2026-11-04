package com.superagent.api.knowledge;

import java.time.Instant;

/** A file or folder in a knowledge base; {@code path} is "/"-separated and relative to its root ("" = root). */
public record KnowledgeEntryDto(String name, String path, boolean dir, long size, Instant modifiedAt) {
}
