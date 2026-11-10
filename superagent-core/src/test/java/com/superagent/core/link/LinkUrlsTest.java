package com.superagent.core.link;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkUrlsTest {

    @Test
    void findsTheLinksAsPeopleWriteThemWithoutTheirSurroundings() {
        String brief = """
                资料：[课程介绍](https://geekbang.feishu.cn/docx/NhGx?from=copylink)，还有 https://example.com/a_(b).
                参考 https://github.com/o/r。再看一次 https://example.com/a_(b)；以及（https://docs.google.com/document/d/x/edit）
                """;
        assertThat(LinkUrls.in(brief)).containsExactly(
                "https://geekbang.feishu.cn/docx/NhGx?from=copylink",
                "https://example.com/a_(b)",
                "https://github.com/o/r",
                "https://docs.google.com/document/d/x/edit");
        assertThat(LinkUrls.in("没有链接")).isEmpty();
    }
}
