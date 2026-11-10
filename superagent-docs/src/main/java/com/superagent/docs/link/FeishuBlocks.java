package com.superagent.docs.link;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A Feishu / Lark document rebuilt as light Markdown from the block map its web page loads (one entry per block:
 * type, text, children). {@code complete} is false when a block lists a child that isn't in the map, i.e. part of the
 * document was never loaded.
 */
final class FeishuBlocks {

    record Rendered(String title, String text, boolean complete) {
    }

    private final JsonNode blocks;
    private final StringBuilder out = new StringBuilder();
    private boolean complete = true;

    private FeishuBlocks(JsonNode blocks) {
        this.blocks = blocks;
    }

    static Rendered render(String rootId, JsonNode blockMap) {
        FeishuBlocks r = new FeishuBlocks(blockMap);
        JsonNode root = blockMap.path(rootId).path("data");
        if (root.isMissingNode()) {
            return new Rendered("", "", false);
        }
        String title = text(root);
        for (String child : children(root)) {
            r.block(child, 0);
        }
        return new Rendered(title, r.out.toString().replaceAll("\\n{3,}", "\n\n").strip(), r.complete);
    }

    private void block(String id, int depth) {
        JsonNode data = blocks.path(id).path("data");
        if (data.isMissingNode()) {
            complete = false;
            return;
        }
        String type = data.path("type").asText();
        String text = text(data);
        String indent = "  ".repeat(depth);
        int childDepth = depth;
        switch (type) {
            case "heading1", "heading2", "heading3", "heading4", "heading5", "heading6", "heading7", "heading8", "heading9" ->
                    out.append('\n').append("#".repeat(Math.min(6, type.charAt(7) - '0'))).append(' ').append(text).append("\n\n");
            case "bullet" -> {
                out.append(indent).append("- ").append(text).append('\n');
                childDepth = depth + 1;
            }
            case "ordered" -> {
                out.append(indent).append("1. ").append(text).append('\n');
                childDepth = depth + 1;
            }
            case "todo" -> {
                out.append(indent).append(data.path("done").asBoolean(false) ? "- [x] " : "- [ ] ").append(text).append('\n');
                childDepth = depth + 1;
            }
            case "code" -> out.append("\n```\n").append(text).append("\n```\n\n");
            case "quote" -> out.append("> ").append(text).append("\n\n");
            case "divider" -> out.append("\n---\n\n");
            case "image" -> out.append("[图片]\n\n");
            case "file" -> out.append("[附件]\n\n");
            case "sheet", "bitable" -> out.append("[内嵌表格，内容未读取]\n\n");
            case "table" -> {
                table(data);
                return;
            }
            default -> {
                if (!text.isBlank()) {
                    out.append(indent).append(text).append("\n\n");
                }
            }
        }
        for (String child : children(data)) {
            block(child, childDepth);
        }
    }

    /** Rows × columns; each cell's own blocks are flattened to one line. */
    private void table(JsonNode data) {
        List<String> rows = ids(data.path("rows_id"));
        List<String> cols = ids(data.path("columns_id"));
        JsonNode cells = data.path("cell_set");
        out.append('\n');
        for (int r = 0; r < rows.size(); r++) {
            out.append('|');
            for (String col : cols) {
                String cellId = cells.path(rows.get(r) + col).path("block_id").asText("");
                out.append(' ').append(cellText(cellId).replace("|", "\\|")).append(" |");
            }
            out.append('\n');
            if (r == 0) {
                out.append('|').append(" --- |".repeat(cols.size())).append('\n');
            }
        }
        out.append('\n');
    }

    private String cellText(String cellId) {
        JsonNode cell = blocks.path(cellId).path("data");
        if (cell.isMissingNode()) {
            if (!cellId.isEmpty()) {
                complete = false;
            }
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String child : children(cell)) {
            JsonNode d = blocks.path(child).path("data");
            if (d.isMissingNode()) {
                complete = false;
            } else if (!text(d).isBlank()) {
                parts.add(text(d).strip());
            }
        }
        return String.join(" ", parts).replace('\n', ' ');
    }

    private static String text(JsonNode data) {
        JsonNode parts = data.path("text").path("initialAttributedTexts").path("text");
        if (!parts.isObject()) {
            return "";
        }
        List<String> keys = new ArrayList<>();
        parts.fieldNames().forEachRemaining(keys::add);
        keys.sort((a, b) -> Integer.compare(num(a), num(b)));
        StringBuilder sb = new StringBuilder();
        keys.forEach(k -> sb.append(parts.path(k).asText("")));
        return sb.toString();
    }

    private static List<String> children(JsonNode data) {
        return ids(data.path("children"));
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
