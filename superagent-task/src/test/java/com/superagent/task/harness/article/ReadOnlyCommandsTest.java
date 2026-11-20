package com.superagent.task.harness.article;

import com.superagent.task.harness.HarnessState.Article.Evidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What an investigation may run (read-only, inside the directory), and the evidence log's own format. */
class ReadOnlyCommandsTest {

    private static final Path REPO = Path.of("").toAbsolutePath().getParent();

    @Test
    void onlyReadersInsideTheDirectoryAreAllowed() {
        for (String ok : List.of("grep -rn \"TenantContext.require\" superagent-task/src/main | wc -l", "git log --oneline -3",
                "cat pom.xml | head -3", "grep -rn \"/api/v1\" superagent-web/src/main/java", "ls superagent-task/src", "find . -name \"*.md\" -maxdepth 1")) {
            assertThat(ReadOnlyCommands.refusal(ok, REPO)).as(ok).isEmpty();
        }
        for (String bad : List.of("rm -rf target", "cat /etc/passwd", "cat ../outside.txt", "grep foo pom.xml > out.txt", "ls; rm x",
                "find . -delete", "find . -exec rm {} +", "git push origin main", "git -c core.pager=x log", "sort -o out.txt pom.xml",
                "cat $HOME/.ssh/id_rsa", "cat `whoami`", "curl http://example.com", "git diff --output=x.patch", "tail -f pom.xml",
                "grep x pom.xml || rm y", "mvn test", "git log --ext-diff", "git log --ext-diff=x", "sort -ofile pom.xml", "sort -uo out pom.xml", "git grep -Oless x", "git grep -nOless x", "rg --pre=sh x", "cat superagent-task/../../outside")) {
            assertThat(ReadOnlyCommands.refusal(bad, REPO)).as(bad).isPresent();
        }
    }

    @Test
    void anAllowedCommandRunsInTheDirectoryAndARefusedOneDoesNot() {
        ReadOnlyCommands.Result ok = ReadOnlyCommands.run(REPO, "cat pom.xml | head -2");
        assertThat(ok.ran()).isTrue();
        assertThat(ok.exit()).isZero();
        assertThat(ok.output()).contains("<?xml");

        ReadOnlyCommands.Result no = ReadOnlyCommands.run(REPO, "cat /etc/hosts");
        assertThat(no.ran()).isFalse();
        assertThat(no.refused()).contains("任务目录之外");
    }

    @Test
    void theEvidenceLogIsParsedAndEachEntryMustPointAtSomething() {
        String log = """
                ## 证据日志
                ### E1 | 代码 | superagent-task/src/main/java/com/superagent/task/harness/article/ArticleChecks.java:1-5
                看到什么：文件头。

                ### E2 | 命令 | grep -c class pom.xml
                输出：
                ```
                0
                ```

                ### E3 | 数字 | 142 行
                环境：本机

                ### E4 | 文档 | Mermaid 文档 | 版本 11.17.0 | https://mermaid.js.org/
                摘录：x
                """;
        List<Evidence> parsed = EvidenceLog.parse(log);
        assertThat(parsed).extracting(Evidence::id).containsExactly("E1", "E2", "E3", "E4");
        assertThat(parsed.get(1).body()).contains("输出");
        assertThat(EvidenceLog.problems(parsed, REPO)).isEmpty();

        assertThat(EvidenceLog.problems(List.of(), REPO)).singleElement().asString().contains("证据日志是空的");
        List<Evidence> bad = List.of(new Evidence("E1", "代码", "Nope.java:3", "x"), new Evidence("E1", "命令", "ls", "没有输出"),
                new Evidence("E3", "数字", "9 ms", "只有数字"), new Evidence("E4", "文档", "只有标题", "x"));
        assertThat(EvidenceLog.problems(bad, REPO)).anyMatch(p -> p.contains("文件不存在")).anyMatch(p -> p.contains("编号重复"))
                .anyMatch(p -> p.contains("没有记录输出")).anyMatch(p -> p.contains("没有写环境")).anyMatch(p -> p.contains("缺版本或链接"));
    }
}
