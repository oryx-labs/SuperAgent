package com.superagent.core.net;

import com.superagent.core.error.ForbiddenException;

/** The server won't connect there for you (see {@link EgressGuard}); the message says why. */
public class EgressDeniedException extends ForbiddenException {

    public EgressDeniedException(String message) {
        super(message);
    }
}
