package com.superagent.event;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.InboxEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * First-pass triage, the way a person scans a message list: deterministic, free, explainable. It only sets
 * the pace (now / hourly / daily digest / not at all); what to do about a message is SuperAgent's call.
 * Users configure nothing: who matters comes from the org chart (manager, direct reports), urgency from a
 * fixed set of signals. Every decision carries a one-line reason shown to the user.
 *
 * <p>Urgent words alone don't make a group message urgent (someone else's "紧急" in a big group isn't
 * yours); they do when the message is addressed to the owner, or comes from someone who matters to them.
 */
public final class TriageRules {

    static final List<String> URGENT_WORDS = List.of("线上故障", "紧急", "加急", "今天必须", "马上", "宕机", "urgent", "asap");
    private static final Set<String> ACKS = Set.of("收到", "好的", "好", "ok", "okay", "嗯", "嗯嗯", "谢谢", "多谢",
            "thanks", "thx", "👍", "1", "+1", "了解", "明白", "[表情]");
    private static final Pattern NO_WORDS = Pattern.compile("^[\\p{Punct}\\p{IsPunctuation}\\p{So}\\s]*$");

    public record Decision(EventPriority priority, String reason) {
    }

    private TriageRules() {
    }

    /**
     * {@code fromOwner}: the owner wrote it themselves; {@code fromBot}: another app/bot sent it;
     * {@code people}: lower-cased names of those who matter to the owner → how ("上级", "下属").
     */
    public static Decision triage(InboxEvent e, boolean fromOwner, boolean fromBot, Map<String, String> people) {
        boolean direct = "p2p".equals(e.chatType());
        if (fromBot) {
            return new Decision(EventPriority.IGNORE, "机器人消息");
        }
        if (fromOwner && !direct) {
            return new Decision(EventPriority.IGNORE, "我自己在群里的发言");
        }
        String text = e.text() == null ? "" : e.text().strip();
        if (ACKS.contains(text.toLowerCase(Locale.ROOT)) || NO_WORDS.matcher(text).matches()) {
            return new Decision(EventPriority.IGNORE, "无需处理的简短回复");
        }
        boolean addressed = direct || e.mentionsMe();
        String sender = e.senderName() == null ? "" : e.senderName().strip();
        String relation = people.get(sender.toLowerCase(Locale.ROOT));
        String urgent = URGENT_WORDS.stream().filter(w -> text.toLowerCase(Locale.ROOT).contains(w)).findFirst().orElse(null);
        if (urgent != null && (addressed || relation != null)) {
            return new Decision(EventPriority.P0, "紧急信号「" + urgent + "」");
        }
        if (relation != null && addressed) {
            return new Decision(EventPriority.P0, relation + "「" + sender + "」" + (direct ? "私聊" : "@我"));
        }
        if (addressed) {
            return new Decision(EventPriority.P1, direct ? "私聊" : "@我");
        }
        return new Decision(EventPriority.P2, relation != null ? relation + "的群消息" : "群消息");
    }
}
