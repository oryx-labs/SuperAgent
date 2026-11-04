package com.superagent.event.feishu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuMessagesTest {

    @Test
    void readsTextPostAndPlaceholders() {
        assertThat(FeishuMessages.text("text", "{\"text\":\"@_user_1 帮忙看下\"}", Map.of("@_user_1", "张三")))
                .isEqualTo("@张三 帮忙看下");
        String post = "{\"title\":\"周报\",\"content\":[[{\"tag\":\"text\",\"text\":\"进度 \"},"
                + "{\"tag\":\"at\",\"user_name\":\"李四\"},{\"tag\":\"a\",\"text\":\"链接\",\"href\":\"https://x\"}]]}";
        assertThat(FeishuMessages.text("post", post, Map.of())).isEqualTo("周报\n进度 @李四链接");
        assertThat(FeishuMessages.text("image", "{\"image_key\":\"k\"}", Map.of())).isEqualTo("[图片]");
        assertThat(FeishuMessages.text("text", "not json", Map.of())).isEqualTo("[无法解析的消息]");
    }
}
