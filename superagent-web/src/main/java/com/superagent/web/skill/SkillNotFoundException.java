package com.superagent.web.skill;

/** A skill (or one of its images) that doesn't exist; mapped to HTTP 404. */
public class SkillNotFoundException extends RuntimeException {

    public SkillNotFoundException(String id) {
        super("No such skill: " + id);
    }
}
