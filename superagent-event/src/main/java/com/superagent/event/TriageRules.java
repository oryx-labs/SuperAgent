package com.superagent.event;

import com.superagent.core.inbox.EventPriority;
import com.superagent.core.inbox.EventSource;
import com.superagent.core.inbox.InboxEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * First-pass triage, the way a person scans a message list: deterministic, free, explainable.
 * Every decision carries a one-line reason shown to the user.
 *
 * <p>Urgent words alone don't make a group message urgent (someone else's "紧急" in a big group isn't
 * yours); they do when the message is addressed to the owner, or comes from a VIP.
 */
public final class TriageRules {

    private static final Set<String> ACKS = Set.of("收到", "好的", "好", "ok", "okay", "嗯", "嗯嗯", "谢谢", "多谢",
            "thanks", "thx", "👍", "1", "+1", "了解", "明白", "[表情]");
    private static final Pattern NO_WORDS = Pattern.compile("^[\\p{Punct}\\p{IsPunctuation}\\p{So}\\s]*$");

    public record Decision(EventPriority priority, String reason) {
    }

    private TriageRules() {
    }

    /** {@code fromOwner}: the owner wrote it themselves; {@code fromBot}: another app/bot sent it. */
    public static Decision triage(EventSource source, InboxEvent e, boolean fromOwner, boolean fromBot) {
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
        String vip = match(lines(source.vipNames()), e.senderName(), true);
        String urgent = match(keywords(source), text, false);
        if (urgent != null && (addressed || vip != null)) {
            return new Decision(EventPriority.P0, "命中紧急关键词「" + urgent + "」");
        }
        if (vip != null && addressed) {
            return new Decision(EventPriority.P0, "重要联系人「" + vip + "」" + (direct ? "私聊" : "@我"));
        }
        if (addressed) {
            return new Decision(EventPriority.P1, direct ? "私聊" : "@我");
        }
        return new Decision(EventPriority.P2, vip != null ? "重要联系人的群消息" : "群消息");
    }

    private static List<String> keywords(EventSource source) {
        List<String> own = lines(source.urgentKeywords());
        return own.isEmpty() ? EventSource.DEFAULT_URGENT_KEYWORDS : own;
    }

    /** VIP names must equal the sender's name; keywords may appear anywhere in the text (case-insensitive). */
    private static String match(List<String> needles, String haystack, boolean exact) {
        if (haystack == null || haystack.isBlank()) {
            return null;
        }
        String h = haystack.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            String k = n.toLowerCase(Locale.ROOT);
            if (exact ? h.strip().equals(k) : h.contains(k)) {
                return n;
            }
        }
        return null;
    }

    static List<String> lines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[\\n,，、;；]")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
