package com.superagent.core.inbox;

import java.util.List;
import java.util.Optional;

public interface InboxTodoStore {

    /** Insert or replace by id. */
    void save(InboxTodo todo);

    Optional<InboxTodo> find(String id);

    /** The owner's to-dos, newest first. */
    List<InboxTodo> list(String owner);
}
