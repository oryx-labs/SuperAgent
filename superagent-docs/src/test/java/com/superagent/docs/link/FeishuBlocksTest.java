package com.superagent.docs.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuBlocksTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rebuildsTheDocumentAndTellsWhenPartOfItNeverLoaded() throws Exception {
        String map = """
                {"root": {"data": {"type": "page", "text": %s, "children": ["h", "b", "t", "img", "p"]}},
                 "h":   {"data": {"type": "heading2", "text": %s}},
                 "b":   {"data": {"type": "bullet", "text": %s, "children": ["b2"]}},
                 "b2":  {"data": {"type": "bullet", "text": %s}},
                 "t":   {"data": {"type": "table", "rows_id": ["r1", "r2"], "columns_id": ["c1", "c2"],
                          "cell_set": {"r1c1": {"block_id": "x1"}, "r1c2": {"block_id": "x2"},
                                       "r2c1": {"block_id": "x3"}, "r2c2": {"block_id": "x4"}}}},
                 "x1": {"data": {"type": "table_cell", "children": ["y1"]}}, "y1": {"data": {"type": "text", "text": %s}},
                 "x2": {"data": {"type": "table_cell", "children": ["y2"]}}, "y2": {"data": {"type": "text", "text": %s}},
                 "x3": {"data": {"type": "table_cell", "children": ["y3"]}}, "y3": {"data": {"type": "text", "text": %s}},
                 "x4": {"data": {"type": "table_cell", "children": ["y4"]}}, "y4": {"data": {"type": "text", "text": %s}},
                 "img": {"data": {"type": "image"}},
                 "p":   {"data": {"type": "text", "text": %s}}}
                """.formatted(t("课程设计"), t("一、长图"), t("角标：", "重磅升级"), t("子项"), t("模块"), t("内容"),
                t("Router"), t("跨信道"), t("从 Agent 执行到企业工作台"));
        JsonNode blocks = JSON.readTree(map);

        FeishuBlocks.Rendered r = FeishuBlocks.render("root", blocks);
        assertThat(r.title()).isEqualTo("课程设计");
        assertThat(r.complete()).isTrue();
        assertThat(r.text()).contains("## 一、长图", "- 角标：重磅升级", "  - 子项", "| 模块 | 内容 |", "| --- | --- |",
                "| Router | 跨信道 |", "[图片]", "从 Agent 执行到企业工作台");

        ((com.fasterxml.jackson.databind.node.ObjectNode) blocks).remove("b2"); // a chunk that was never loaded
        assertThat(FeishuBlocks.render("root", blocks).complete()).isFalse();
    }

    /** Feishu's text field: the string in numbered pieces. */
    private static String t(String... pieces) {
        StringBuilder sb = new StringBuilder("{\"initialAttributedTexts\": {\"text\": {");
        for (int i = 0; i < pieces.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(i).append("\": \"").append(pieces[i]).append('"');
        }
        return sb.append("}}}").toString();
    }
}
