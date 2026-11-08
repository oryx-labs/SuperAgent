package com.superagent.event.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.superagent.core.integration.IntegrationChanged;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.EventSourceStore;
import com.superagent.core.inbox.InboxEvent;
import com.superagent.core.tenant.CompanyStarted;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserStore;
import com.superagent.event.InboxService;
import com.superagent.platform.integration.IntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The company's Feishu app (set up under System Settings → integrations), connected over Feishu's long connection (the server dials out, so no public
 * callback URL is needed). One app serves everyone: each received message goes to the inbox of the employees
 * it concerns, and SuperAgent's alerts go back to each employee's own Feishu DM. The handler returns at once
 * because Feishu redelivers events not acknowledged within seconds.
 *
 * <p>Who is who: employees are matched to their Feishu account by phone number (contact API, on connect and
 * hourly); anyone it can't match DMs the bot "绑定 &lt;code&gt;" with the code shown on the Connectors page.
 * Its connection state is reported to {@link IntegrationService}, which shows it to administrators.
 *
 * <p>Each company has its own app and so its own connection, kept by company (two companies may even use the same
 * app id). Everything a connection does — receiving, matching, sending — runs as its company.
 * Routing: a DM to the bot belongs to its sender; a group message goes to every linked member of the chat
 * (triage decides how much it matters to each of them).
 */
@Component
public class FeishuConnector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FeishuConnector.class);
    private static final Pattern BIND = Pattern.compile("^\\s*(?:绑定|bind)\\s*([A-Za-z0-9]{6,16})\\s*$", Pattern.CASE_INSENSITIVE);
    /** Anyone in the tenant can DM the bot, so a sender gets only a few wrong guesses. */
    private static final int MAX_BIND_FAILURES = 5;
    private static final int PHONE_BATCH = 50;
    private static final Duration MEMBERS_TTL = Duration.ofMinutes(10);
    private static final Duration HINT_EVERY = Duration.ofHours(6);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DIGEST_TIME = "09:00";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationService integrations;
    private final EventSourceStore links;
    private final UserStore users;
    private final InboxService inbox;
    private final Map<String, Integer> bindFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> hinted = new ConcurrentHashMap<>();
    private final ExecutorService work = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "feishu-inbox");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "feishu-sync");
        t.setDaemon(true);
        return t;
    });
    /** companyId → that company's live connection. */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<String, Object> reconnecting = new ConcurrentHashMap<>();
    private volatile boolean running;

    /** A company's live app: the API client, the long connection, and lookups cached for its lifetime. */
    private static final class Connection {
        final String companyId;
        final String appId;
        final Client api;
        volatile com.lark.oapi.ws.Client ws;
        final Map<String, String> names = new ConcurrentHashMap<>();
        final Map<String, String> chats = new ConcurrentHashMap<>();
        final Map<String, Members> members = new ConcurrentHashMap<>();

        Connection(String companyId, String appId, Client api) {
            this.companyId = companyId;
            this.appId = appId;
            this.api = api;
        }
    }

    private record Members(Set<String> openIds, Instant fetchedAt) {
    }

    public FeishuConnector(IntegrationService integrations, EventSourceStore links, UserStore users, InboxService inbox) {
        this.integrations = integrations;
        this.links = links;
        this.users = users;
        this.inbox = inbox;
        inbox.registerNotifier(EventSource.FEISHU, this::notifyOwner);
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
        if (IntegrationService.FEISHU.equals(changed.key()) && running && TenantContext.current().isPresent()) {
            work.execute(TenantContext.wrap(this::reconnect));
        }
    }

    /** The current company's connection: closes the old one and opens one if its app is set up. */
    private void reconnect() {
        String companyId = TenantContext.require();
        synchronized (reconnecting.computeIfAbsent(companyId, k -> new Object())) {
            disconnect(connections.remove(companyId));
            if (integrations.ready(IntegrationService.FEISHU)) {
                connect(companyId);
            }
        }
    }

    /** Verifies the credentials (fetching the bot's own id), then opens the long connection on its own thread. */
    private void connect(String companyId) {
        String appId = integrations.value(IntegrationService.FEISHU, "appId").orElse(null);
        String secret = integrations.value(IntegrationService.FEISHU, "appSecret").orElse(null);
        if (appId == null || secret == null) {
            setStatus("error", "App Secret 无法读取，请在系统设置里重新填写");
            return;
        }
        setStatus("connecting", null);
        // Bounded, so an unreachable Feishu shows up as an error instead of a check that never returns.
        // Lark is the same platform under another domain: the app was made on one of them.
        com.lark.oapi.core.enums.BaseUrlEnum base = IntegrationService.FEISHU_DOMAIN_LARK.equals(
                integrations.value(IntegrationService.FEISHU, "domain").orElse(null))
                ? com.lark.oapi.core.enums.BaseUrlEnum.LarkSuite : com.lark.oapi.core.enums.BaseUrlEnum.FeiShu;
        Client api = Client.newBuilder(appId, secret).openBaseUrl(base).requestTimeout(15, TimeUnit.SECONDS).build();
        String botOpenId;
        try {
            botOpenId = botOpenId(api);
        } catch (Exception e) {
            setStatus("error", describe(e));
            return;
        }
        Connection conn = new Connection(companyId, appId, api);
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        work.execute(() -> TenantContext.run(conn.companyId, () -> receive(conn, event)));
                    }
                })
                .build();
        conn.ws = new com.lark.oapi.ws.Client.Builder(appId, secret).domain(base.getUrl())
                .eventHandler(dispatcher).autoReconnect(true).build();
        connections.put(companyId, conn);
        Thread t = new Thread(() -> TenantContext.run(conn.companyId, () -> {
            try {
                conn.ws.start(); // connects (retrying itself on failure), then pings on its own executor
                if (live(conn)) {
                    setStatus("connected", null);
                    log.info("feishu app {} connected for company {} (bot {})", appId, conn.companyId, botOpenId);
                    syncIdentities(conn);
                }
            } catch (RuntimeException e) {
                setStatus("error", "长连接启动失败：" + e.getMessage());
            }
        }), "feishu-ws");
        t.setDaemon(true);
        t.start();
    }

    /** GET /bot/v3/info: the bot's own open_id, for the log; doubles as a credentials check. */
    private static String botOpenId(Client api) throws Exception {
        JsonNode body = call(api.get("/open-apis/bot/v3/info", null, AccessTokenType.Tenant));
        return body.path("bot").path("open_id").asText(null);
    }

    // --- who is who ---

    /**
     * Links every employee with a phone number to their Feishu account, by asking the contact API who has that
     * number. Someone already linked under this app keeps their link (it may have come from a bind code).
     */
    void syncIdentities(Connection conn) {
        Map<String, User> byMobile = new LinkedHashMap<>();
        for (User u : users.listUsers()) {
            String mobile = mobile(u.phone());
            if (u.enabled() && mobile != null) {
                byMobile.put(mobile, u);
            }
        }
        List<String> mobiles = new ArrayList<>(byMobile.keySet());
        int linked = 0;
        for (int i = 0; i < mobiles.size(); i += PHONE_BATCH) {
            List<String> chunk = mobiles.subList(i, Math.min(mobiles.size(), i + PHONE_BATCH));
            JsonNode found;
            try {
                ObjectNode req = JSON.createObjectNode();
                chunk.forEach(req.putArray("mobiles")::add);
                req.put("include_resigned", false);
                found = call(conn.api.post("/open-apis/contact/v3/users/batch_get_id?user_id_type=open_id", req,
                        AccessTokenType.Tenant));
            } catch (Exception e) {
                log.warn("feishu: can't match employees by phone (does the app have the contact permission?): {}",
                        e.getMessage());
                return;
            }
            for (JsonNode hit : found.path("user_list")) {
                User u = byMobile.get(mobile(hit.path("mobile").asText(null)));
                String openId = hit.path("user_id").asText("");
                if (u != null && !openId.isEmpty() && link(conn, u.username(), openId, false)) {
                    linked++;
                }
            }
        }
        if (linked > 0) {
            log.info("feishu: linked {} employees by phone", linked);
        }
    }

    /** Records {@code openId} as {@code owner}'s account under this app; returns whether anything changed. */
    private boolean link(Connection conn, String owner, String openId, boolean replace) {
        EventSource cur = links.find(owner, EventSource.FEISHU).orElse(null);
        if (cur != null && cur.linkedTo(conn.appId) && (!replace || openId.equals(cur.ownerExternalId()))) {
            return false;
        }
        // A Feishu account belongs to one employee: whoever had it under this app before loses it.
        links.list().stream().filter(s -> EventSource.FEISHU.equals(s.type()) && !s.owner().equals(owner)
                        && s.linkedTo(conn.appId) && openId.equals(s.ownerExternalId()))
                .forEach(s -> links.save(s.withBinding(s.appId(), null)));
        links.save(cur == null
                ? EventSource.create(owner, EventSource.FEISHU, conn.appId, openId, null, DIGEST_TIME, ZONE.getId(),
                LocalDate.now(ZONE))
                : cur.withBinding(conn.appId, openId));
        return true;
    }

    /** open_id → username for everyone linked under this app. */
    private Map<String, String> owners(Connection conn) {
        Map<String, String> out = new HashMap<>();
        links.listEnabled(EventSource.FEISHU).stream().filter(s -> s.linkedTo(conn.appId))
                .forEach(s -> out.put(s.ownerExternalId(), s.owner()));
        return out;
    }

    // --- receiving ---

    private void receive(Connection conn, P2MessageReceiveV1 event) {
        try {
            if (!live(conn) || event.getEvent() == null) {
                return;
            }
            EventMessage m = event.getEvent().getMessage();
            EventSender sender = event.getEvent().getSender();
            String senderOpenId = sender != null && sender.getSenderId() != null ? sender.getSenderId().getOpenId() : null;
            boolean fromBot = sender != null && "app".equals(sender.getSenderType());

            Map<String, String> mentionNames = new LinkedHashMap<>();
            Set<String> mentioned = new HashSet<>();
            if (m.getMentions() != null) {
                for (MentionEvent mention : m.getMentions()) {
                    mentionNames.put(mention.getKey(), mention.getName());
                    if (mention.getId() != null && mention.getId().getOpenId() != null) {
                        mentioned.add(mention.getId().getOpenId());
                    }
                }
            }
            String text = FeishuMessages.text(m.getMessageType(), m.getContent(), mentionNames);
            String raw = Jsons.DEFAULT.toJson(event);
            Map<String, String> owners = owners(conn);

            if ("p2p".equals(m.getChatType())) {
                if (senderOpenId == null || bind(conn, senderOpenId, text)) {
                    return;
                }
                String owner = owners.get(senderOpenId);
                if (owner == null) {
                    hint(conn, senderOpenId);
                    return;
                }
                deliver(conn, owner, m, raw, senderOpenId, text, true, true, fromBot);
                return;
            }
            Set<String> recipients = new LinkedHashSet<>();
            mentioned.stream().filter(owners::containsKey).forEach(recipients::add);
            members(conn, m.getChatId()).stream().filter(owners::containsKey).forEach(recipients::add);
            recipients.remove(senderOpenId); // your own group messages aren't news to you
            for (String openId : recipients) {
                deliver(conn, owners.get(openId), m, raw, senderOpenId, text, mentioned.contains(openId), false, fromBot);
            }
        } catch (RuntimeException e) {
            log.warn("feishu message dropped: {}", e.getMessage());
        }
    }

    private void deliver(Connection conn, String owner, EventMessage m, String raw, String senderOpenId, String text,
                         boolean mentionsOwner, boolean fromOwner, boolean fromBot) {
        EventSource source = links.find(owner, EventSource.FEISHU).orElse(null);
        if (source == null || !source.enabled()) {
            return;
        }
        boolean direct = "p2p".equals(m.getChatType());
        InboxEvent e = new InboxEvent(
                UUID.nameUUIDFromBytes(("feishu:" + m.getMessageId() + ":" + owner).getBytes(StandardCharsets.UTF_8)).toString(),
                owner, EventSource.FEISHU, m.getMessageId(), m.getChatId(),
                direct ? "私聊" : chatName(conn, m.getChatId()), m.getChatType(),
                m.getThreadId() != null ? m.getThreadId() : m.getRootId(),
                senderOpenId, userName(conn, senderOpenId), text, direct || mentionsOwner, raw,
                EventPriority.P2, null, false, null, millis(m.getCreateTime()), Instant.now(), Instant.now());
        inbox.ingest(source, e, fromOwner, fromBot);
    }

    /** "绑定 XXXX" with the code from the Connectors page: links the sender's Feishu account to that employee. */
    private boolean bind(Connection conn, String senderOpenId, String text) {
        Matcher m = BIND.matcher(text == null ? "" : text);
        if (!m.matches()) {
            return false;
        }
        String tries = conn.companyId + "/" + senderOpenId;
        if (bindFailures.getOrDefault(tries, 0) >= MAX_BIND_FAILURES) {
            send(conn, senderOpenId, "绑定码错误次数过多，请联系管理员。");
            return true;
        }
        Optional<EventSource> match = links.list().stream()
                .filter(s -> EventSource.FEISHU.equals(s.type()) && m.group(1).equalsIgnoreCase(s.bindCode()))
                .findFirst();
        if (match.isPresent()) {
            bindFailures.remove(tries);
            link(conn, match.get().owner(), senderOpenId, true);
            log.info("feishu: {} linked by bind code", match.get().owner());
            send(conn, senderOpenId, "已关联。发给我的消息和群里与你有关的消息会交给你的 SuperAgent，紧急的事会私聊提醒你。");
        } else {
            bindFailures.merge(tries, 1, Integer::sum);
            send(conn, senderOpenId, "绑定码不正确或已失效，请在 SuperAgent 的「连接」页面重新生成。");
        }
        return true;
    }

    /** Someone the app doesn't know DMed the bot: tell them how to link, now and then. */
    private void hint(Connection conn, String openId) {
        String who = conn.companyId + "/" + openId;
        Instant last = hinted.get(who);
        if (last != null && Instant.now().isBefore(last.plus(HINT_EVERY))) {
            return;
        }
        hinted.put(who, Instant.now());
        send(conn, openId, "你的飞书账号还没有关联 SuperAgent。请在 SuperAgent 的「连接」页面找到飞书，按提示把绑定码发给我。");
    }

    /** Pushes a short alert to an employee's Feishu DM (needs them linked under their company's current app). */
    private void notifyOwner(EventSource source, String text) {
        Connection conn = connections.get(TenantContext.require());
        if (conn == null || !source.linkedTo(conn.appId)) {
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
                log.warn("feishu send failed: {} {}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.warn("feishu send failed: {}", e.getMessage());
        }
    }

    // --- lookups (cached per connection) ---

    /** A chat's members' open_ids; needs the chat-member permission, so a failure means "mentions only". */
    private Set<String> members(Connection conn, String chatId) {
        if (chatId == null) {
            return Set.of();
        }
        Members cached = conn.members.get(chatId);
        if (cached != null && Instant.now().isBefore(cached.fetchedAt().plus(MEMBERS_TTL))) {
            return cached.openIds();
        }
        Set<String> ids = new HashSet<>();
        String page = null;
        try {
            for (int i = 0; i < 20; i++) {
                String path = "/open-apis/im/v1/chats/" + chatId + "/members?member_id_type=open_id&page_size=100"
                        + (page == null ? "" : "&page_token=" + page);
                JsonNode data = call(conn.api.get(path, null, AccessTokenType.Tenant));
                data.path("items").forEach(it -> ids.add(it.path("member_id").asText()));
                page = data.path("has_more").asBoolean(false) ? data.path("page_token").asText(null) : null;
                if (page == null) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("feishu: members of {} unavailable: {}", chatId, e.getMessage());
        }
        conn.members.put(chatId, new Members(Set.copyOf(ids), Instant.now()));
        return ids;
    }

    /**
     * Display name via the contact API; needs a contact permission, so a failure falls back to the id. Only found
     * names are kept: one granted later shows up without reconnecting.
     */
    private static String userName(Connection conn, String openId) {
        if (openId == null) {
            return null;
        }
        String name = conn.names.computeIfAbsent(openId, id -> {
            try {
                GetUserResp resp = conn.api.contact().v3().user().get(GetUserReq.newBuilder()
                        .userId(id).userIdType("open_id").build());
                if (resp.success() && resp.getData() != null && resp.getData().getUser() != null
                        && resp.getData().getUser().getName() != null) {
                    return resp.getData().getUser().getName();
                }
            } catch (Exception ignored) {
                // permission missing or transient error: show the id this time
            }
            return null;
        });
        return name != null ? name : openId;
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

    /** A Feishu open-API reply's {@code data}, or an exception carrying its error message. */
    private static JsonNode call(RawResponse resp) throws Exception {
        JsonNode body = JSON.readTree(new String(resp.getBody(), StandardCharsets.UTF_8));
        if (body.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(body.path("msg").asText("HTTP " + resp.getStatusCode()));
        }
        return body.has("data") ? body.path("data") : body;
    }

    /** Digits (and a leading +) only, so "138 0000-0000" and "13800000000" are the same number. */
    static String mobile(String phone) {
        if (phone == null) {
            return null;
        }
        String s = phone.strip().replaceAll("[\\s()-]", "");
        return s.matches("\\+?\\d{5,20}") ? s : null;
    }

    private boolean live(Connection conn) {
        return connections.get(conn.companyId) == conn;
    }

    /** For the current company (every caller runs as the connection's company). */
    private void setStatus(String status, String error) {
        integrations.setStatus(IntegrationService.FEISHU, status, error);
        if (error != null) {
            log.warn("feishu ({}): {}", TenantContext.require(), error);
        }
    }

    /**
     * The SDK's long-connection client has no public close: turn off its auto-reconnect and call its
     * protected disconnect, then stop its ping executor, so an old connection doesn't linger after the
     * administrator changes or disables the app.
     */
    private void disconnect(Connection conn) {
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
            log.warn("feishu: could not close the old connection cleanly: {}", e.getMessage());
        }
    }

    /** Tells "the server can't reach Feishu" apart from "Feishu rejected the credentials". */
    private static String describe(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException || t instanceof java.io.InterruptedIOException) {
                return "无法连接飞书 / Lark 开放平台，请检查服务器的网络或代理，以及「版本」是否选对";
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

    // --- lifecycle ---

    /** Connections open per company as each starts ({@link CompanyStarted}, after this). */
    @Override
    public void start() {
        running = true;
        ticker.scheduleWithFixedDelay(() -> connections.values().forEach(conn -> {
            try {
                TenantContext.run(conn.companyId, () -> syncIdentities(conn));
            } catch (RuntimeException e) {
                log.warn("feishu: phone matching failed for company {}: {}", conn.companyId, e.getMessage());
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
