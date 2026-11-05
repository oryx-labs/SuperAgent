package com.superagent.knowledge;

/**
 * One knowledge base: a user's own ({@code personal}), a team's ({@code team}, keyed by its path), or the
 * whole company's. Clients name it as {@code personal}, {@code team:<path>} or {@code company}.
 */
public record KnowledgeSpace(Kind kind, String key) {

    public enum Kind { PERSONAL, TEAM, COMPANY }

    public static KnowledgeSpace personal(String owner) {
        return new KnowledgeSpace(Kind.PERSONAL, owner);
    }

    public static KnowledgeSpace team(String path) {
        return new KnowledgeSpace(Kind.TEAM, path);
    }

    public static KnowledgeSpace company() {
        return new KnowledgeSpace(Kind.COMPANY, "");
    }

    /** The client-facing name: {@code personal}, {@code team:<path>} or {@code company}. */
    public String id() {
        return switch (kind) {
            case PERSONAL -> "personal";
            case TEAM -> "team:" + key;
            case COMPANY -> "company";
        };
    }
}
