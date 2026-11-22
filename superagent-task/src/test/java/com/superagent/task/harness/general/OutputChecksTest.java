package com.superagent.task.harness.general;

import com.superagent.task.harness.HarnessHost.Materials;
import com.superagent.task.harness.HarnessHost.Produced;
import com.superagent.task.harness.HarnessState.Deliverable;
import com.superagent.task.harness.HarnessState.PlatformCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform's own checks of the work: files there and not empty, of the kind asked for, materials read, formats valid. */
class OutputChecksTest {

    private static final Materials NONE = new Materials(List.of(), List.of(), List.of());

    @Test
    void filesMustExistBeOfTheKindAskedForAndParse() {
        List<Deliverable> srt = List.of(new Deliverable("字幕", "srt"), new Deliverable("说明", "markdown"));

        assertThat(failing(OutputChecks.run(srt, List.of(), NONE, "做好了"))).containsExactly(OutputChecks.FILES);
        assertThat(OutputChecks.run(srt, List.of(), NONE, "做好了").get(0).detail()).contains("没有交付任何文件");

        List<PlatformCheck> wrongKind = OutputChecks.run(srt, List.of(text("字幕.txt", "1\n..."), text("说明.md", "# 说明")), NONE, null);
        assertThat(failing(wrongKind)).containsExactly(OutputChecks.TYPES);
        assertThat(wrongKind.get(1).detail()).contains("要求 srt");

        List<PlatformCheck> broken = OutputChecks.run(srt, List.of(text("字幕.srt", "1\n00:00:05,000 --> 00:00:01,000\n倒了\n"),
                text("说明.md", " ")), NONE, null);
        assertThat(failing(broken)).containsExactly(OutputChecks.FILES, OutputChecks.FORMATS);
        assertThat(broken.get(2).detail()).contains("时间轴倒退");

        assertThat(OutputChecks.parse("json", "{\"a\": [1, 2}")).contains("JSON");
        assertThat(OutputChecks.parse("csv", "a,b\n1,\"x,y\"\n2,3,4")).contains("第 3 行");
        assertThat(OutputChecks.parse("csv", "a,b\n1,\"x,y\"")).isNull();
        // A binary file is only checked for being there, not empty, and of the right kind.
        assertThat(failing(OutputChecks.run(List.of(new Deliverable("演示", "pptx")),
                List.of(new Produced("p", "演示.pptx", "application/x", 2048, null)), NONE, null))).isEmpty();
    }

    @Test
    void materialsMustBeReadAndAnAnswerOnlyTaskIsCheckedOnItsReply() {
        Materials unread = new Materials(List.of("资料A.md", "资料B.md"), List.of("资料B.md"), List.of("https://x.example/doc"));
        PlatformCheck m = OutputChecks.run(List.of(), List.of(), unread, "结论").stream()
                .filter(c -> c.name().equals(OutputChecks.MATERIALS)).findFirst().orElseThrow();
        assertThat(m.passed()).isFalse();
        assertThat(m.detail()).contains("资料B.md", "https://x.example/doc").doesNotContain("资料A.md");

        assertThat(OutputChecks.run(List.of(new Deliverable("结论", "reply")), List.of(), NONE, "  "))
                .singleElement().satisfies(c -> {
                    assertThat(c.name()).isEqualTo(OutputChecks.REPLY);
                    assertThat(c.passed()).isFalse();
                });
    }

    private static Produced text(String name, String content) {
        return new Produced(name, name, "text/plain", content.length(), content);
    }

    private static List<String> failing(List<PlatformCheck> checks) {
        return checks.stream().filter(c -> !c.passed()).map(PlatformCheck::name).toList();
    }

    @Test
    void aiSoundingMarkdownGetsAWarningThatNeverFailsTheCheck() {
        String heavy = "# 方案\n\n## 现状\n\n" + "值得注意的是，赋能业务，综上所述，全方位助力闭环。".repeat(10);
        List<PlatformCheck> checks = OutputChecks.run(List.of(new Deliverable("方案", "md")), List.of(text("方案.md", heavy)), NONE, null,
                com.superagent.core.writing.StyleLexicon.FALLBACK);
        assertThat(failing(checks)).isEmpty();
        assertThat(checks).anySatisfy(c -> assertThat(c).matches(PlatformCheck::warning).matches(PlatformCheck::passed)
                .matches(x -> x.name().startsWith(OutputChecks.WRITING) && x.detail().contains("套话")));
        String clean = "# 方案\n\n## 现状\n\n用 `Cache` 缓存 60 秒，P95 从 420 ms 到 85 ms。\n";
        assertThat(OutputChecks.run(List.of(), List.of(text("方案.md", clean)), NONE, "好", com.superagent.core.writing.StyleLexicon.FALLBACK))
                .noneMatch(PlatformCheck::warning);
        assertThat(OutputChecks.run(List.of(), List.of(text("方案.md", heavy)), NONE, "好")).noneMatch(PlatformCheck::warning); // no list: no text check
    }

}
