package com.superagent.core.link;

/**
 * What a link holds. {@code via}: {@link #PUBLIC} (read without logging in) or {@link #AUTHORIZED} (as the user, through
 * an integration they authorized). {@code complete} is false when only part of it could be read (a very long document,
 * a page that kept loading), so nobody mistakes the part for the whole.
 */
public record LinkContent(String url, String title, String text, String via, boolean complete) {

    public static final String PUBLIC = "public";
    public static final String AUTHORIZED = "authorized";
}
