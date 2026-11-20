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

    @Test
    void articleIsAKindToolsAcceptWhileTheRetiredWritingKindIsNotPickedUpByItsFlow() {
        com.fasterxml.jackson.databind.node.ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        assertThat(TaskKinds.require(args.put("kind", "article"))).isEqualTo("article");
        assertThat(TaskKinds.label("article")).isEqualTo("写作");
        assertThat(TaskKinds.DESCRIPTION).contains("article =");
        assertThat(TaskService.resolveKind(req("写一篇公众号文章", null, "article"))).isEqualTo(TaskKind.ARTICLE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TaskKinds.require(args.put("kind", "writing")))
                .isInstanceOf(com.superagent.runtime.mcp.McpToolException.class);
        // Harnesses are looked up by the stored flow name: an old writing row never reaches the article flow.
        assertThat(new com.superagent.task.harness.article.ArticleHarness().kind()).isEqualTo("article").isNotEqualTo("writing");
    }

    @Test
    void aWritingTaskCanNameItsGenreAndTheToolSaysHowItIsChosen() {
        com.fasterxml.jackson.databind.node.ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        assertThat(TaskKinds.genre(args)).isNull();
        assertThat(TaskKinds.genre(args.put("genre", " 技术方案 "))).isEqualTo("技术方案");
        assertThat(TaskKinds.GENRE_DESCRIPTION).contains("article_genres", "通用长文", "技术调研", "repo");
        assertThat(TaskKinds.DESCRIPTION).contains("genre", "通用长文");
        com.superagent.task.TaskMcpTools tools = new com.superagent.task.TaskMcpTools(null);
        assertThat(tools.tools()).anySatisfy(t -> assertThat(t.path("inputSchema").path("properties").has("genre")).isTrue())
                .anySatisfy(t -> assertThat(t.path("name").asText()).isEqualTo("article_genres"));
    }
}
