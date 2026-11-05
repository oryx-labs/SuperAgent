package com.superagent.api.knowledge;

/**
 * A knowledge base the user can open. {@code id} is what the knowledge endpoints take as {@code space};
 * {@code kind}: personal | team | company; {@code team} is the team path for team spaces.
 */
public record KnowledgeSpaceDto(String id, String kind, String team, boolean canWrite) {
}
