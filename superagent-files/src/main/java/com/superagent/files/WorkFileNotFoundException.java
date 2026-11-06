package com.superagent.files;

import com.superagent.core.error.NotFoundException;

/** No such file, or not visible to the caller. */
public class WorkFileNotFoundException extends NotFoundException {

    public WorkFileNotFoundException(String id) {
        super("文件不存在：" + id);
    }
}
