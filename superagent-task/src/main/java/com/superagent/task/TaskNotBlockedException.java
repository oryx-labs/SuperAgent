package com.superagent.task;

import com.superagent.core.error.ConflictException;

/** "Run it now" on a task that isn't stuck: it has started, waits for nothing, or is only waiting (it starts by itself). */
public class TaskNotBlockedException extends ConflictException {

    public TaskNotBlockedException(String id) {
        super("任务 " + id + " 没有被阻塞（没有在等失败或已停止的任务），不需要直接跑：只是在等的任务会在前面的任务完成后自动开始");
    }
}
