package com.superagent.event.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** DingTalk Stream frames: every one is acknowledged with its message id, pings echo their data, text is read out. */
class DingTalkConnectorTest {

    private final ObjectMapper json = new ObjectMapper();

    private static final class FakeSocket implements WebSocket {
        final List<String> sent = new ArrayList<>();
        boolean aborted;
        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) { sent.add(data.toString()); return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) { return CompletableFuture.completedFuture(this); }
        @Override public void request(long n) { }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return false; }
        @Override public boolean isInputClosed() { return false; }
        @Override public void abort() { aborted = true; }
    }

    private DingTalkConnector connector() {
        return new DingTalkConnector(mock(com.superagent.platform.integration.IntegrationService.class),
                mock(com.superagent.core.inbox.EventSourceStore.class), mock(com.superagent.core.user.UserStore.class),
                mock(com.superagent.event.InboxService.class), "http://127.0.0.1:1", "http://127.0.0.1:1");
    }

    @Test
    void framesAreAcknowledgedAndPingsEchoed() throws Exception {
        DingTalkConnector c = connector();
        FakeSocket ws = new FakeSocket();
        DingTalkConnector.Connection conn = c.new Connection("ding-app", "s");
        c.handleFrame(conn, ws, "{\"type\":\"SYSTEM\",\"headers\":{\"topic\":\"ping\",\"messageId\":\"p1\"},\"data\":\"{\\\"opaque\\\":1}\"}");
        c.handleFrame(conn, ws, "{\"type\":\"CALLBACK\",\"headers\":{\"topic\":\"" + DingTalkConnector.BOT_TOPIC
                + "\",\"messageId\":\"m1\"},\"data\":\"{}\"}");
        assertThat(ws.sent).hasSize(2);
        JsonNode ping = json.readTree(ws.sent.get(0));
        assertThat(ping.path("code").asInt()).isEqualTo(200);
        assertThat(ping.path("headers").path("messageId").asText()).isEqualTo("p1");
        assertThat(ping.path("data").asText()).isEqualTo("{\"opaque\":1}");
        assertThat(json.readTree(ws.sent.get(1)).path("headers").path("messageId").asText()).isEqualTo("m1");

        c.handleFrame(conn, ws, "{\"type\":\"SYSTEM\",\"headers\":{\"topic\":\"disconnect\",\"messageId\":\"d1\"}}");
        assertThat(ws.aborted).isTrue();
        c.stop();
    }

    @Test
    void readsTheTextOfEachKindOfMessage() throws Exception {
        assertThat(DingTalkConnector.text(json.readTree("{\"msgtype\":\"text\",\"text\":{\"content\":\"  周报写好了吗 \"}}")))
                .isEqualTo("周报写好了吗");
        assertThat(DingTalkConnector.text(json.readTree(
                "{\"msgtype\":\"richText\",\"content\":{\"richText\":[{\"text\":\"看下\"},{\"downloadCode\":\"x\"},{\"text\":\"这个\"}]}}")))
                .isEqualTo("看下这个");
        assertThat(DingTalkConnector.text(json.readTree("{\"msgtype\":\"file\",\"content\":{\"fileName\":\"方案.pdf\"}}")))
                .isEqualTo("[文件] 方案.pdf");
        assertThat(DingTalkConnector.mobile("+86 138-0013-8000")).isEqualTo("13800138000");
    }
}
