package com.superagent.event.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/** Turns a Feishu message's {@code content} JSON into readable text. Non-text types become a short placeholder. */
final class FeishuMessages {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CHARS = 8000;

    private FeishuMessages() {
    }

    /** {@code mentions}: mention key (e.g. "@_user_1") → display name, to restore readable @names. */
    static String text(String messageType, String content, Map<String, String> mentions) {
        String out;
        try {
            JsonNode c = content == null || content.isBlank() ? JSON.createObjectNode() : JSON.readTree(content);
            out = switch (messageType == null ? "" : messageType) {
                case "text" -> c.path("text").asText("");
                case "post" -> post(c);
                case "image" -> "[图片]";
                case "file" -> "[文件] " + c.path("file_name").asText("");
                case "audio" -> "[语音]";
                case "media" -> "[视频] " + c.path("file_name").asText("");
                case "sticker" -> "[表情]";
                case "interactive" -> "[卡片消息]";
                case "share_chat" -> "[分享群聊]";
                case "share_user" -> "[分享名片]";
                case "merge_forward" -> "[合并转发的聊天记录]";
                default -> "[" + messageType + "]";
            };
        } catch (IOException e) {
            out = "[无法解析的消息]";
        }
        for (Map.Entry<String, String> m : mentions.entrySet()) {
            out = out.replace(m.getKey(), "@" + m.getValue());
        }
        out = out.strip();
        return out.length() <= MAX_CHARS ? out : out.substring(0, MAX_CHARS) + "…";
    }

    /** Rich text: optional title, then paragraphs of inline elements (text, links, @mentions, images). */
    private static String post(JsonNode c) {
        // Some payloads wrap the body per locale ({"zh_cn": {...}}).
        if (!c.has("content") && c.size() > 0) {
            c = c.elements().next();
        }
        StringBuilder sb = new StringBuilder();
        String title = c.path("title").asText("");
        if (!title.isBlank()) {
            sb.append(title).append('\n');
        }
        for (JsonNode paragraph : c.path("content")) {
            for (JsonNode el : paragraph) {
                switch (el.path("tag").asText("")) {
                    case "text", "a" -> sb.append(el.path("text").asText(""));
                    case "at" -> sb.append('@').append(el.path("user_name").asText(el.path("user_id").asText("")));
                    case "img" -> sb.append("[图片]");
                    case "media" -> sb.append("[视频]");
                    case "emotion" -> sb.append("[表情]");
                    case "code_block" -> sb.append(el.path("text").asText(""));
                    default -> { }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
