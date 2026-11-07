package com.superagent.task;

import com.superagent.api.task.CreateTaskRequest;
import com.superagent.core.task.TaskKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Coding only when the creator says so or names a repository; never guessed from words like 实现 or 测试. */
class TaskKindTest {

    private static CreateTaskRequest req(String brief, String repo, String kind) {
        return new CreateTaskRequest("改造写作 skill", brief, null, repo, null, null, null, null, kind);
    }

    @Test
    void generalUnlessTheCreatorSaysCodingOrNamesARepository() {
        assertThat(TaskService.resolveKind(req("实现新的写作规范，构建后测试一遍接口说明", null, null))).isEqualTo(TaskKind.GENERAL);
        assertThat(TaskService.resolveKind(req("x", "git@example.com:a/b.git", null))).isEqualTo(TaskKind.CODING);
        assertThat(TaskService.resolveKind(req("x", null, "coding"))).isEqualTo(TaskKind.CODING);
        assertThat(TaskService.resolveKind(req("x", "repo", "general"))).isEqualTo(TaskKind.GENERAL);
        assertThat(TaskKind.fromStorage("writing")).isEqualTo(TaskKind.GENERAL); // a retired kind on an old row
    }
}
