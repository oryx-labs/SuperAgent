package com.superagent.project;

import com.superagent.core.error.ConflictException;

/** The project is archived: everything in it is read-only until it's reopened. */
public class ProjectArchivedException extends ConflictException {

    public ProjectArchivedException() {
        super("项目已归档，资料、记忆和仓库配置都只读");
    }
}
