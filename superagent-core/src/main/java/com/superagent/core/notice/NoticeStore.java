package com.superagent.core.notice;

import java.util.List;
import java.util.Optional;

public interface NoticeStore {

    /** Insert or replace by id. */
    void save(Notice notice);

    Optional<Notice> find(String id);

    Optional<Notice> findByKey(String owner, String key);

    /** The owner's items, newest first, at most {@code limit}. */
    List<Notice> list(String owner, int limit);
}
