package com.superagent.knowledge.skill;

import com.superagent.core.error.NotFoundException;

/** A skill (or one of its images) that doesn't exist; mapped to HTTP 404. */
public class SkillNotFoundException extends NotFoundException {

    public SkillNotFoundException(String id) {
        super("No such skill: " + id);
    }
}
