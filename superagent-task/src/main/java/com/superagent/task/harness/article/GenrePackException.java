package com.superagent.task.harness.article;

/** A genre pack skill whose configuration block is wrong; the message says which skill and which field. */
public class GenrePackException extends IllegalArgumentException {

    public GenrePackException(String pack, String field, String problem) {
        super("文体包「" + pack + "」的配置有误：" + (field == null ? "" : "字段 " + field + "：") + problem);
    }
}
