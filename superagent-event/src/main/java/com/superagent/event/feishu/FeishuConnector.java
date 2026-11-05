package com.superagent.event.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.contact.v3.model.GetUserResp;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.GetChatReq;
import com.lark.oapi.service.im.v1.model.GetChatResp;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceChanged;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.security.SecretCipher;
import com.superagent.event.InboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each user's own Feishu bot, connected over Feishu's long connection (the server dials out, so no
 * public callback URL is needed). Received messages are normalized and handed to {@link InboxService};
 * the handler returns at once because Feishu redelivers events not acknowledged within seconds.
 *
 * <p>Binding: the user sends their bot "绑定 &lt;code&gt;" (the code shown in SuperAgent); that DM's
 * sender becomes the owner's Feishu identity, used to spot mentions of them and to DM them alerts.
 */
@Component
public class FeishuConnector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuConnector.class);
    private static final Pattern BIND = Pattern.compile("^\\s*(?:绑定|bind)\\s*([A-Za-z0-9]{6,16})\\s*$", Pattern.CASE_INSENSITIVE);
    /** Anyone in the tenant can DM the bot, so a code survives only a few wrong guesses. */
    private static final int MAX_BIND_FAILURES = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventSourceStore sources;
    private final InboxService inbox;
    private final SecretCipher cipher;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Integer> bindFailures = new ConcurrentHashMap<>();
    private final ExecutorService work = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "feishu-inbox");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;

    /** One user's live bot: the API client, the long connection, and lookups cached for its lifetime. */
    private static final class Connection {
        final String owner;
        final Client api;
        final String botOpenId;
        volatile com.lark.oapi.ws.Client ws;
        final Map<String, String> names = new ConcurrentHashMap<>();
        final Map<String, String> chats = new ConcurrentHashMap<>();

        Connection(String owner, Client api, String botOpenId) {
            this.owner = owner;
            this.api = api;
            this.botOpenId = botOpenId;
        }
    }

    public FeishuConnector(EventSourceStore sources, InboxService inbox, SecretCipher cipher) {
        this.sources = sources;
        this.inbox = inbox;
        this.cipher = cipher;
        inbox.registerNotifier(EventSource.FEISHU, this::notifyOwner);
    }

    @EventListener
    public void onChanged(EventSourceChanged changed) {
        if (EventSource.FEISHU.equals(changed.type()) && running) {
            work.execute(() -> reconnect(changed.owner()));
        }
    }

    private synchronized void reconnect(String owner) {
        disconnect(owner);
        sources.find(owner, EventSource.FEISHU).filter(EventSource::enabled).ifPresent(this::connect);
    }

    /** Verifies the credentials (fetching the bot's own id), then opens the long connection on its own thread. */
    private void connect(EventSource source) {
        String owner = source.owner();
        if (source.appId() == null || source.secretCipher() == null) {
            setStatus(owner, "error", "App ID 或 App Secret 未填写");
            return;
        }
        String secret;
        try {
            secret = cipher.decrypt(source.secretCipher());
        } catch (IllegalStateException e) {
            setStatus(owner, "error", "App Secret 无法解密，请重新填写");
            return;
        }
        Client api = Client.newBuilder(source.appId(), secret).build();
        String botOpenId;
        try {
            botOpenId = botOpenId(api);
        } catch (Exception e) {
            setStatus(owner, "error", describe(e));
            return;
        }
        Connection conn = new Connection(owner, api, botOpenId);
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        work.execute(() -> receive(conn, event));
                    }
                })
                .build();
        conn.ws = new com.lark.oapi.ws.Client.Builder(source.appId(), secret)
                .eventHandler(dispatcher).autoReconnect(true).build();
        connections.put(owner, conn);
        setStatus(owner, "connecting", null);
        Thread t = new Thread(() -> {
            try {
                conn.ws.start(); // connects (retrying itself on failure), then pings on its own executor
                if (connections.get(owner) == conn) {
                    setStatus(owner, "connected", null);
                    log.info("feishu bot for {} connected (bot {})", owner, botOpenId);
                }
            } catch (RuntimeException e) {
                setStatus(owner, "error", "长连接启动失败：" + e.getMessage());
            }
        }, "feishu-ws-" + owner);
        t.setDaemon(true);
        t.start();
    }

    /** GET /bot/v3/info: the bot's own open_id (to recognise "@bot"); doubles as a credentials check. */
    private static String botOpenId(Client api) throws Exception {
        RawResponse resp = api.get("/open-apis/bot/v3/info", null, AccessTokenType.Tenant);
        JsonNode body = JSON.readTree(new String(resp.getBody(), StandardCharsets.UTF_8));
        if (body.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(body.path("msg").asText("HTTP " + resp.getStatusCode()));
        }
        return body.path("bot").path("open_id").asText(null);
    }

    private void receive(Connection conn, P2MessageReceiveV1 event) {
        try {
            EventSource source = sources.find(conn.owner, EventSource.FEISHU).orElse(null);
            if (source == null || !source.enabled() || event.getEvent() == null) {
                return;
            }
            EventMessage m = event.getEvent().getMessage();
            EventSender sender = event.getEvent().getSender();
            String senderOpenId = sender != null && sender.getSenderId() != null ? sender.getSenderId().getOpenId() : null;
            boolean fromBot = sender != null && "app".equals(sender.getSenderType());

            Map<String, String> mentionNames = new LinkedHashMap<>();
            boolean mentionsMe = false;
            if (m.getMentions() != null) {
                for (MentionEvent mention : m.getMentions()) {
                    mentionNames.put(mention.getKey(), mention.getName());
                    String id = mention.getId() == null ? null : mention.getId().getOpenId();
                    if (id != null && (id.equals(conn.botOpenId) || id.equals(source.ownerExternalId()))) {
                        mentionsMe = true;
                    }
                }
            }
            String text = FeishuMessages.text(m.getMessageType(), m.getContent(), mentionNames);
            boolean direct = "p2p".equals(m.getChatType());

            if (direct && bind(conn, source, senderOpenId, text)) {
                return;
            }
            InboxEvent e = new InboxEvent(
                    UUID.nameUUIDFromBytes(("feishu:" + m.getMessageId()).getBytes(StandardCharsets.UTF_8)).toString(),
                    conn.owner, EventSource.FEISHU, m.getMessageId(), m.getChatId(),
                    direct ? "私聊" : chatName(conn, m.getChatId()), m.getChatType(),
                    m.getThreadId() != null ? m.getThreadId() : m.getRootId(),
                    senderOpenId, userName(conn, senderOpenId), text, direct || mentionsMe, Jsons.DEFAULT.toJson(event),
                    EventPriority.P2, null, false, null, millis(m.getCreateTime()), Instant.now(), Instant.now());
            boolean fromOwner = senderOpenId != null && senderOpenId.equals(source.ownerExternalId());
            inbox.ingest(source, e, fromOwner, fromBot);
        } catch (RuntimeException e) {
            log.warn("feishu message for {} dropped: {}", conn.owner, e.getMessage());
        }
    }

    /** "绑定 123456" with the code shown in SuperAgent: remembers who the owner is on Feishu. */
    private boolean bind(Connection conn, EventSource source, String senderOpenId, String text) {
        Matcher m = BIND.matcher(text);
        if (!m.matches() || senderOpenId == null) {
            return false;
        }
        boolean ok = source.bindCode() != null && source.bindCode().equalsIgnoreCase(m.group(1));
        if (ok) {
            bindFailures.remove(conn.owner);
            sources.save(source.withBinding(senderOpenId));
            log.info("feishu bot for {} bound to {}", conn.owner, senderOpenId);
        } else if (source.bindCode() != null
                && bindFailures.merge(conn.owner, 1, Integer::sum) >= MAX_BIND_FAILURES) {
            bindFailures.remove(conn.owner);
            sources.save(source.withBindCode(null)); // burn it: the owner generates a new one in SuperAgent
            log.warn("feishu bot for {}: bind code invalidated after {} wrong attempts", conn.owner, MAX_BIND_FAILURES);
        }
        send(conn, senderOpenId, ok ? "已绑定。紧急消息会私聊提醒你，其他消息会整理进 SuperAgent 的秘书对话。"
                : "绑定码不正确或已失效，请在 SuperAgent 的收件箱设置里重新生成。");
        return true;
    }

    /** Pushes a short alert to the owner's Feishu DM (needs a completed bind). */
    private void notifyOwner(EventSource source, String text) {
        Connection conn = connections.get(source.owner());
        if (conn == null || source.ownerExternalId() == null) {
            return;
        }
        send(conn, source.ownerExternalId(), text);
    }

    private void send(Connection conn, String openId, String text) {
        try {
            String content = JSON.createObjectNode().put("text", text).toString();
            CreateMessageResp resp = conn.api.im().message().create(CreateMessageReq.newBuilder()
                    .receiveIdType("open_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(openId).msgType("text").content(content).build())
                    .build());
            if (!resp.success()) {
                log.warn("feishu send for {} failed: {} {}", conn.owner, resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.warn("feishu send for {} failed: {}", conn.owner, e.getMessage());
        }
    }

    /** Display name via the contact API; needs a contact permission, so a failure just falls back to the id. */
    private static String userName(Connection conn, String openId) {
        if (openId == null) {
            return null;
        }
        return conn.names.computeIfAbsent(openId, id -> {
            try {
                GetUserResp resp = conn.api.contact().v3().user().get(GetUserReq.newBuilder()
                        .userId(id).userIdType("open_id").build());
                if (resp.success() && resp.getData() != null && resp.getData().getUser() != null
                        && resp.getData().getUser().getName() != null) {
                    return resp.getData().getUser().getName();
                }
            } catch (Exception ignored) {
                // permission missing or transient error: keep the id
            }
            return id;
        });
    }

    private static String chatName(Connection conn, String chatId) {
        if (chatId == null) {
            return null;
        }
        return conn.chats.computeIfAbsent(chatId, id -> {
            try {
                GetChatResp resp = conn.api.im().v1().chat().get(GetChatReq.newBuilder().chatId(id).build());
                if (resp.success() && resp.getData() != null && resp.getData().getName() != null) {
                    return resp.getData().getName();
                }
            } catch (Exception ignored) {
                // fall back to the id
            }
            return id;
        });
    }

    private void setStatus(String owner, String status, String error) {
        sources.find(owner, EventSource.FEISHU).ifPresent(s -> sources.save(s.withStatus(status, error)));
        if (error != null) {
            log.warn("feishu bot for {}: {}", owner, error);
        }
    }

    /**
     * The SDK's long-connection client has no public close: turn off its auto-reconnect and call its
     * protected disconnect, then stop its ping executor, so an old connection doesn't linger after the
     * user changes or disables their bot.
     */
    private void disconnect(String owner) {
        Connection conn = connections.remove(owner);
        if (conn == null || conn.ws == null) {
            return;
        }
        try {
            Field auto = com.lark.oapi.ws.Client.class.getDeclaredField("autoReconnect");
            auto.setAccessible(true);
            auto.set(conn.ws, Boolean.FALSE);
            Method close = com.lark.oapi.ws.Client.class.getDeclaredMethod("disconnect");
            close.setAccessible(true);
            close.invoke(conn.ws);
            Field executor = com.lark.oapi.ws.Client.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((ExecutorService) executor.get(conn.ws)).shutdownNow();
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("feishu bot for {}: could not close the old connection cleanly: {}", owner, e.getMessage());
        }
    }

    /** Tells "the server can't reach Feishu" apart from "Feishu rejected the credentials". */
    private static String describe(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException || t instanceof java.io.InterruptedIOException) {
                return "无法连接飞书开放平台（open.feishu.cn），请检查服务器的网络或代理";
            }
        }
        return "飞书拒绝了连接（请检查 App ID / App Secret 和应用权限）：" + e.getMessage();
    }

    private static Instant millis(String ms) {
        try {
            return ms == null ? null : Instant.ofEpochMilli(Long.parseLong(ms));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** For the UI: whether this owner has a live connection object. */
    public Optional<String> botOpenId(String owner) {
        return Optional.ofNullable(connections.get(owner)).map(c -> c.botOpenId);
    }

    // --- lifecycle ---

    @Override
    public void start() {
        running = true;
        work.execute(() -> sources.listEnabled(EventSource.FEISHU).forEach(s -> reconnect(s.owner())));
    }

    @Override
    public void stop() {
        running = false;
        connections.keySet().forEach(this::disconnect);
        work.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
