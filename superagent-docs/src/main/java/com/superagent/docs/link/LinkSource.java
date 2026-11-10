package com.superagent.docs.link;

import com.superagent.core.link.LinkContent;

import java.net.URI;

/** One platform's way to read its public links without logging in. Throws UnreadableLinkException when it can't. */
interface LinkSource {

    boolean handles(URI uri);

    LinkContent read(URI uri, String link, Fetcher fetcher);
}
