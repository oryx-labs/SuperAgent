package com.superagent.web.skill;

/** The caller may not do this to the skill (e.g. delete someone else's); mapped to HTTP 403. */
public class SkillForbiddenException extends RuntimeException {

    public SkillForbiddenException(String message) {
        super(message);
    }
}
