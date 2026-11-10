package com.superagent.docs.link;

import com.superagent.core.link.UnreadableLinkException;
import com.superagent.core.net.EgressDeniedException;
import com.superagent.core.net.EgressGuard;

import java.net.URI;

/** {@link EgressGuard#PUBLIC_ONLY} for reading links: a refusal becomes "this link can't be read" with the reason. */
final class UrlGuard {

    private UrlGuard() {
    }

    static URI check(String raw) {
        try {
            return EgressGuard.PUBLIC_ONLY.check(raw);
        } catch (EgressDeniedException e) {
            throw new UnreadableLinkException(raw, e.getMessage(), null);
        }
    }

    static URI toUri(String raw) {
        try {
            return EgressGuard.toUri(raw);
        } catch (EgressDeniedException e) {
            throw new UnreadableLinkException(raw, e.getMessage(), null);
        }
    }
}
