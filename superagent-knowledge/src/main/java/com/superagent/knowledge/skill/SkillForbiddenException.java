package com.superagent.knowledge.skill;

import com.superagent.core.error.ForbiddenException;

/** The caller may not do this to the skill (e.g. delete someone else's); mapped to HTTP 403. */
public class SkillForbiddenException extends ForbiddenException {

    public SkillForbiddenException(String message) {
        super(message);
    }
}
