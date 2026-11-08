package com.superagent.project;

import com.superagent.core.error.NotFoundException;

public class ProjectNotFoundException extends NotFoundException {

    public ProjectNotFoundException(String id) {
        super("Project not found: " + id);
    }
}
