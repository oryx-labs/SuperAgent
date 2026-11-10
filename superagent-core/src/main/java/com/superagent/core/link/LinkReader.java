package com.superagent.core.link;

/**
 * Reads what a link points at, for agents and for checking a task's materials before it is created. Public material
 * is read as anyone on the internet would see it, with no login; what needs an account is read as the user who asked,
 * through the integrations they authorized. Never as the company or the app: nobody reads more through an agent than
 * they could read themselves.
 */
public interface LinkReader {

    /**
     * The link's content as Markdown-ish text. {@code user} is who is asking (their authorizations are tried when the
     * public read gets nothing). Throws {@link UnreadableLinkException} with the reason and what would fix it.
     */
    LinkContent read(String url, String user);
}
