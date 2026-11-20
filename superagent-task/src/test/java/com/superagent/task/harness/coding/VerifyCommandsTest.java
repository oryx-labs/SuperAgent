package com.superagent.task.harness.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyCommandsTest {

    @TempDir
    Path root;

    private void module(String id, String... deps) throws Exception {
        StringBuilder xml = new StringBuilder("<project><parent><artifactId>p</artifactId></parent><artifactId>" + id
                + "</artifactId><dependencies>");
        for (String d : deps) {
            xml.append("<dependency><groupId>g</groupId><artifactId>").append(d).append("</artifactId></dependency>");
        }
        Files.writeString(Files.createDirectories(root.resolve(id)).resolve("pom.xml"), xml.append("</dependencies></project>"));
    }

    @Test
    void aCommandWhoseModuleAnotherAmChainCoversIsDroppedAndTheRestStayAsWritten() throws Exception {
        module("core");
        module("runtime", "core");
        module("web", "runtime");
        module("docs", "core");

        var m = VerifyCommands.merge(List.of("mvn -pl superagent-x -am test", "mvn -pl runtime -am test", "mvn -pl docs -am test",
                "npm test", "mvn -o -q test", "mvn -pl web -am test"), root);

        assertThat(m.commands()).containsExactly("mvn -pl superagent-x -am test", "mvn -pl docs -am test", "npm test",
                "mvn -o -q test", "mvn -pl web -am test");
        assertThat(m.dropped()).singleElement().asString().contains("mvn -pl runtime -am test", "mvn -pl web -am test");

        // Different goals, or nothing recognisable: nothing changes.
        var same = List.of("mvn -pl runtime -am test", "mvn -pl web -am verify", "npm test");
        assertThat(VerifyCommands.merge(same, root).commands()).isEqualTo(same);
    }

    @Test
    void indentedSubPointsStayWithTheirCriterion() {
        var spec = CodingPrompts.parseSpec("## 验收标准\n1. 单测覆盖：\n   - 甲；\n   - 乙。\n2. 通过\n\n## 验证命令\n```\nmvn test\n```");
        assertThat(spec.criteria()).containsExactly("单测覆盖： 甲； 乙。", "通过");
    }
}
