package com.superagent.event.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.integration.IntegrationChanged;
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.event.InboxService;
import com.superagent.platform.integration.IntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The company's DingTalk app (System Settings → integrations), connected in Stream mode: the server dials out to
 * DingTalk over a WebSocket, so no public callback is needed. Works like the Feishu connector: one app for
 * everyone, employees matched to their DingTalk account by phone (else they DM the bot "绑定 &lt;code&gt;"), and
 * alerts sent back to each employee through the bot.
 *
 * <p>DingTalk only hands a bot the messages meant for it: a one-to-one chat with the bot, or a group message that
 * @-mentions it. Such a message is the sender talking to their SuperAgent; anyone else it @-mentions who is linked
 * gets it too. Every message must be acknowledged at once, or DingTalk sends it again.
 *
 * <p>Each company has its own app and so its own connection, kept by company; everything a connection does runs
 * as its company.
 */
@Component
public class DingTalkConnector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DingTalkConnector.class);
    static final String BOT_TOPIC = "/v1.0/im/bot/messages/get";
    private static final Pattern BIND = Pattern.compile("^\\s*(?:绑定|bind)\\s*([A-Za-z0-9]{6,16})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_BIND_FAILURES = 5;
    private static final Duration HINT_EVERY = Duration.ofHours(6);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DIGEST_TIME = "09:00";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationService integrations;
    private final EventSourceStore links;
    private final UserStore users;
    private final InboxService inbox;
    private final String api;
    private final String oapi;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Integer> bindFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> hinted = new ConcurrentHashMap<>();
    private final ExecutorService work = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "dingtalk-inbox");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dingtalk-sync");
        t.setDaemon(true);
        return t;
    });
    /** companyId → that company's live connection. */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Object> reconnecting = new ConcurrentHashMap<>();
    private volatile boolean running;

    /** A company's live app: its credentials, an access token kept fresh, and the stream socket. */
    final class Connection {
        final String companyId;
        final String clientId;
        final String clientSecret;
        volatile WebSocket socket;
        volatile String token;
        volatile Instant tokenUntil = Instant.EPOCH;
        volatile boolean closed;
        int attempts;

        Connection(String companyId, String clientId, String clientSecret) {
            this.companyId = companyId;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        synchronized String token() throws IOException, InterruptedException {
            if (token == null || Instant.now().isAfter(tokenUntil)) {
                JsonNode r = post(api + "/v1.0/oauth2/accessToken", null,
                        JSON.createObjectNode().put("appKey", clientId).put("appSecret", clientSecret));
                token = r.path("accessToken").asText(null);
                if (token == null) {
                    throw new IOException("钉钉没有返回 access token：" + r);
                }
                tokenUntil = Instant.now().plusSeconds(Math.max(60, r.path("expireIn").asLong(7200) - 300));
            }
            return token;
        }
    }

    public DingTalkConnector(IntegrationService integrations, EventSourceStore links, UserStore users, InboxService inbox,
                             @Value("${superagent.dingtalk.api-url:https://api.dingtalk.com}") String api,
                             @Value("${superagent.dingtalk.oapi-url:https://oapi.dingtalk.com}") String oapi) {
        this.integrations = integrations;
        this.links = links;
        this.users = users;
        this.inbox = inbox;
        this.api = trim(api);
        this.oapi = trim(oapi);
        inbox.registerNotifier(EventSource.DINGTALK, this::notifyOwner);
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @EventListener
    public void onCompanyStarted(CompanyStarted started) {
        if (running) {
            work.execute(TenantContext.wrap(this::reconnect));
        }
    }

    /** Published inside the company whose app changed: only that company's connection is redone. */
    @EventListener
    public void onChanged(IntegrationChanged changed) {
        if (IntegrationService.DINGTALK.equals(changed.key()) && running && TenantContext.current().isPresent()) {
            work.execute(TenantContext.wrap(this::reconnect));
        }
    }

    /** The current company's connection: closes the old one and opens one if its app is set up. */
    void reconnect() {
        String companyId = TenantContext.require();
        synchronized (reconnecting.computeIfAbsent(companyId, k -> new Object())) {
            disconnect(connections.remove(companyId));
            if (integrations.ready(IntegrationService.DINGTALK)) {
                String id = integrations.value(IntegrationService.DINGTALK, "clientId").orElse(null);
                String secret = integrations.value(IntegrationService.DINGTALK, "clientSecret").orElse(null);
                if (id == null || secret == null) {
                    setStatus(companyId, "error", "Client Secret 无法读取，请在系统设置里重新填写");
                    return;
                }
                Connection conn = new Connection(companyId, id, secret);
                connections.put(companyId, conn);
                open(conn);
            }
        }
    }

    /** The current company's connection, if it has one. */
    Connection connection() {
        return connections.get(TenantContext.require());
    }

    private boolean live(Connection conn) {
        return connections.get(conn.companyId) == conn;
    }

    private static void disconnect(Connection conn) {
        if (conn != null) {
            conn.closed = true;
            WebSocket s = conn.socket;
            if (s != null) {
                s.abort();
            }
        }
    }

    /** Checks the credentials, asks for a stream endpoint, and opens the socket; retries with backoff on failure. */
    private void open(Connection conn) {
        if (conn.closed || !live(conn)) {
            return;
        }
        setStatus(conn.companyId, "connecting", null);
        try {
            conn.token();
            ObjectNode req = JSON.createObjectNode().put("clientId", conn.clientId).put("clientSecret", conn.clientSecret)
                    .put("ua", "superagent").put("localIp", "");
            req.putArray("subscriptions").addObject().put("type", "CALLBACK").put("topic", BOT_TOPIC);
            JsonNode ep = post(api + "/v1.0/gateway/connections/open", null, req);
            String endpoint = ep.path("endpoint").asText(null);
            String ticket = ep.path("ticket").asText(null);
            if (endpoint == null || ticket == null) {
                throw new IOException("钉钉没有返回 Stream 地址：" + ep);
            }
            conn.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(URI.create(endpoint + "?ticket=" + URLEncoder.encode(ticket, StandardCharsets.UTF_8)),
                            new Listener(conn)).get(20, TimeUnit.SECONDS);
            conn.attempts = 0;
            setStatus(conn.companyId, "connected", null);
            log.info("dingtalk app {} connected for company {}", conn.clientId, conn.companyId);
            work.execute(() -> TenantContext.run(conn.companyId, () -> syncIdentities(conn)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            setStatus(conn.companyId, "error", describe(e));
            retry(conn);
        }
    }

    private void retry(Connection conn) {
        if (conn.closed || !live(conn) || !running) {
            return;
        }
        long wait = Math.min(300, 5L << Math.min(conn.attempts++, 6)); // 5s, 10s, … up to 5 min
        ticker.schedule(() -> TenantContext.run(conn.companyId, () -> open(conn)), wait, TimeUnit.SECONDS);
    }

    /** Frames arrive as text, possibly in parts; each whole one is acknowledged and handled off the socket thread. */
    private final class Listener implements WebSocket.Listener {
        private final Connection conn;
        private final StringBuilder buf = new StringBuilder();

        Listener(Connection conn) {
            this.conn = conn;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String frame = buf.toString();
                buf.setLength(0);
                handleFrame(conn, ws, frame);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (!conn.closed && live(conn)) {
                log.info("dingtalk stream closed for company {} ({} {}), reconnecting", conn.companyId, status, reason);
                setStatus(conn.companyId, "connecting", null);
                retry(conn);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!conn.closed && live(conn)) {
                setStatus(conn.companyId, "error", "连接中断：" + error.getMessage());
                retry(conn);
            }
        }
    }

    void handleFrame(Connection conn, WebSocket ws, String frame) {
        JsonNode f;
        try {
            f = JSON.readTree(frame);
        } catch (IOException e) {
            log.warn("dingtalk: unreadable frame dropped");
            return;
        }
        String type = f.path("type").asText("");
        String topic = f.path("headers").path("topic").asText("");
        String messageId = f.path("headers").path("messageId").asText("");
        if ("SYSTEM".equals(type)) {
            if ("ping".equals(topic)) {
                ack(ws, messageId, f.path("data").asText(""));
            } else if ("disconnect".equals(topic)) {
                log.info("dingtalk asked to reconnect");
                ws.abort(); // onClose / onError won't fire after abort: reconnect ourselves
                retry(conn);
            }
            return;
        }
        ack(ws, messageId, "{\"response\":null}"); // at once: DingTalk resends what isn't acknowledged in time
        if (BOT_TOPIC.equals(topic)) {
            String data = f.path("data").asText("");
            work.execute(() -> TenantContext.run(conn.companyId, () -> receive(conn, data)));
        }
    }

    private static void ack(WebSocket ws, String messageId, String data) {
        ObjectNode a = JSON.createObjectNode().put("code", 200).put("message", "OK").put("data", data);
        a.putObject("headers").put("contentType", "application/json").put("messageId", messageId);
        ws.sendText(a.toString(), true);
    }

    // --- who is who ---

    /** Links every employee with a phone number to their DingTalk account (userid), one lookup each. */
    void syncIdentities(Connection conn) {
        int linked = 0;
        for (User u : users.listUsers()) {
            String mobile = mobile(u.phone());
            if (!u.enabled() || mobile == null || !live(conn)) {
                continue;
            }
            EventSource cur = links.find(u.id(), EventSource.DINGTALK).orElse(null);
            if (cur != null && cur.linkedTo(conn.clientId)) {
                continue; // already linked under this app (maybe by a bind code)
            }
            try {
                JsonNode r = post(oapi + "/topapi/v2/user/getbymobile?access_token=" + conn.token(), null,
                        JSON.createObjectNode().put("mobile", mobile));
                String userId = r.path("result").path("userid").asText("");
                if (!userId.isEmpty() && link(conn, u.id(), userId, false)) {
                    linked++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                log.warn("dingtalk: can't match {} by phone (does the app have the contact permission?): {}",
                        u.username(), e.getMessage());
                return;
            }
        }
        if (linked > 0) {
            log.info("dingtalk: linked {} employees by phone", linked);
        }
    }

    private boolean link(Connection conn, String owner, String userId, boolean replace) {
        EventSource cur = links.find(owner, EventSource.DINGTALK).orElse(null);
        if (cur != null && cur.linkedTo(conn.clientId) && (!replace || userId.equals(cur.ownerExternalId()))) {
            return false;
        }
        links.list().stream().filter(s -> EventSource.DINGTALK.equals(s.type()) && !s.owner().equals(owner)
                        && s.linkedTo(conn.clientId) && userId.equals(s.ownerExternalId()))
                .forEach(s -> links.save(s.withBinding(s.appId(), null)));
        links.save(cur == null
                ? EventSource.create(owner, EventSource.DINGTALK, conn.clientId, userId, null, DIGEST_TIME, ZONE.getId(),
                LocalDate.now(ZONE))
                : cur.withBinding(conn.clientId, userId));
        return true;
    }

    private Optional<String> ownerOf(Connection conn, String userId) {
        return links.listEnabled(EventSource.DINGTALK).stream()
                .filter(s -> s.linkedTo(conn.clientId) && userId.equals(s.ownerExternalId()))
                .map(EventSource::owner).findFirst();
    }

    // --- receiving ---

    void receive(Connection conn, String data) {
        try {
            if (!live(conn)) {
                return;
            }
            JsonNode m = JSON.readTree(data);
            String sender = m.path("senderStaffId").asText("");
            String text = text(m);
            boolean single = "1".equals(m.path("conversationType").asText());
            if (sender.isEmpty()) {
                return;
            }
            if (single && bind(conn, sender, text)) {
                return;
            }
            Set<String> recipients = new LinkedHashSet<>();
            ownerOf(conn, sender).ifPresent(recipients::add);
            if (recipients.isEmpty() && single) {
                hint(conn, sender);
                return;
            }
            for (JsonNode at : m.path("atUsers")) {
                String staff = at.path("staffId").asText("");
                if (!staff.isEmpty() && !staff.equals(sender)) {
                    ownerOf(conn, staff).ifPresent(recipients::add);
                }
            }
            String senderOwner = ownerOf(conn, sender).orElse(null);
            for (String owner : recipients) {
                deliver(owner, m, data, sender, text, single, owner.equals(senderOwner));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("dingtalk message dropped: {}", e.getMessage());
        }
    }

    /** The message's text; other kinds get a short placeholder (their content isn't fetched). */
    static String text(JsonNode m) {
        String type = m.path("msgtype").asText("text");
        return switch (type) {
            case "text" -> m.path("text").path("content").asText("").strip();
            case "richText" -> {
                StringBuilder sb = new StringBuilder();
                m.path("content").path("richText").forEach(p -> sb.append(p.path("text").asText("")));
                yield sb.toString().strip();
            }
            case "picture" -> "[图片]";
            case "audio" -> m.path("content").path("recognition").asText("[语音]");
            case "video" -> "[视频]";
            case "file" -> "[文件] " + m.path("content").path("fileName").asText("");
            default -> "[" + type + "]";
        };
    }

    /**
     * The sender's own copy counts as a message to their SuperAgent even from a group (they @-mentioned the bot to
     * reach it), so it isn't taken for one of their own group remarks and ignored.
     */
    private void deliver(String owner, JsonNode m, String raw, String sender, String text, boolean single, boolean fromOwner) {
        EventSource source = links.find(owner, EventSource.DINGTALK).orElse(null);
        if (source == null || !source.enabled()) {
            return;
        }
        String msgId = m.path("msgId").asText(UUID.randomUUID().toString());
        String chatId = m.path("conversationId").asText(null);
        long at = m.path("createAt").asLong(0);
        InboxEvent e = new InboxEvent(
                UUID.nameUUIDFromBytes(("dingtalk:" + msgId + ":" + owner).getBytes(StandardCharsets.UTF_8)).toString(),
                owner, EventSource.DINGTALK, msgId, chatId,
                single ? "私聊" : m.path("conversationTitle").asText("群聊") + (fromOwner ? "（@机器人）" : ""),
                single || fromOwner ? "p2p" : "group", null,
                sender, m.path("senderNick").asText(null), text, true, raw,
                EventPriority.P2, null, false, null, at > 0 ? Instant.ofEpochMilli(at) : Instant.now(), Instant.now(), Instant.now());
        inbox.ingest(source, e, fromOwner, false);
    }

    private boolean bind(Connection conn, String userId, String text) {
        Matcher m = BIND.matcher(text == null ? "" : text);
        if (!m.matches()) {
            return false;
        }
        String tries = conn.companyId + "/" + userId;
        if (bindFailures.getOrDefault(tries, 0) >= MAX_BIND_FAILURES) {
            send(conn, userId, "绑定码错误次数过多，请联系管理员。");
            return true;
        }
        Optional<EventSource> match = links.list().stream()
                .filter(s -> EventSource.DINGTALK.equals(s.type()) && m.group(1).equalsIgnoreCase(s.bindCode()))
                .findFirst();
        if (match.isPresent()) {
            bindFailures.remove(tries);
            link(conn, match.get().owner(), userId, true);
            log.info("dingtalk: {} linked by bind code", match.get().owner());
            send(conn, userId, "已关联。私聊我或在群里 @我 的消息会交给你的 SuperAgent，紧急的事会私聊提醒你。");
        } else {
            bindFailures.merge(tries, 1, Integer::sum);
            send(conn, userId, "绑定码不正确或已失效，请在 SuperAgent 的「连接」页面重新生成。");
        }
        return true;
    }

    private void hint(Connection conn, String userId) {
        String who = conn.companyId + "/" + userId;
        Instant last = hinted.get(who);
        if (last != null && Instant.now().isBefore(last.plus(HINT_EVERY))) {
            return;
        }
        hinted.put(who, Instant.now());
        send(conn, userId, "你的钉钉账号还没有关联 SuperAgent。请在 SuperAgent 的「连接」页面找到钉钉，按提示把绑定码发给我。");
    }

    /** Called inside the owner's company: sends through that company's app. */
    private void notifyOwner(EventSource source, String text) {
        Connection conn = connection();
        if (conn == null || !source.linkedTo(conn.clientId)) {
            return;
        }
        send(conn, source.ownerExternalId(), text);
    }

    /** A one-to-one message from the app's bot (robotCode is the app's client id for an app bot). */
    private void send(Connection conn, String userId, String text) {
        try {
            ObjectNode body = JSON.createObjectNode().put("robotCode", conn.clientId).put("msgKey", "sampleText")
                    .put("msgParam", JSON.createObjectNode().put("content", text).toString());
            body.putArray("userIds").add(userId);
            post(api + "/v1.0/robot/oToMessages/batchSend", conn.token(), body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("dingtalk send failed: {}", e.getMessage());
        }
    }

    // --- helpers ---

    private JsonNode post(String url, String token, JsonNode body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (token != null) {
            b.header("x-acs-dingtalk-access-token", token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        if (r.statusCode() >= 300 || json.path("errcode").asInt(0) != 0) {
            String msg = json.path("message").asText(json.path("errmsg").asText(r.body()));
            throw new IOException("HTTP " + r.statusCode() + " " + msg);
        }
        return json;
    }

    static String mobile(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("86") && digits.length() == 13) {
            digits = digits.substring(2);
        }
        return digits.length() >= 6 ? digits : null;
    }

    private static String describe(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("40089") || m.toLowerCase().contains("invalid") && m.toLowerCase().contains("app")) {
            return "Client ID 或 Client Secret 不正确：" + m;
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException) {
            return "无法连接钉钉开放平台，请检查服务器的网络或代理";
        }
        return m;
    }

    /** Socket callbacks run on the HTTP client's threads, so the company is set here rather than assumed. */
    private void setStatus(String companyId, String status, String error) {
        TenantContext.run(companyId, () -> integrations.setStatus(IntegrationService.DINGTALK, status, error));
        if (error != null) {
            log.warn("dingtalk ({}): {}", companyId, error);
        }
    }

    // --- lifecycle ---

    /** Connections open per company as each starts ({@link CompanyStarted}, after this). */
    @Override
    public void start() {
        running = true;
        ticker.scheduleWithFixedDelay(() -> connections.values().forEach(conn -> {
            if (conn.socket == null) {
                return;
            }
            try {
                TenantContext.run(conn.companyId, () -> syncIdentities(conn));
            } catch (RuntimeException e) {
                log.warn("dingtalk: phone matching failed for company {}: {}", conn.companyId, e.getMessage());
            }
        }), 1, 1, TimeUnit.HOURS);
    }

    @Override
    public void stop() {
        running = false;
        connections.keySet().forEach(id -> disconnect(connections.remove(id)));
        ticker.shutdownNow();
        work.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
