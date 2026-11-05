package com.superagent.core.org;

/**
 * Published after a team (and the teams under it) moved from {@code from} to {@code to}, so whatever is
 * kept per team — e.g. its knowledge base — can follow.
 */
public record TeamRenamed(String from, String to) {
}
