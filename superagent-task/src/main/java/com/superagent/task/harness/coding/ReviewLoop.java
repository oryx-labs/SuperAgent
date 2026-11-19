package com.superagent.task.harness.coding;

import com.superagent.task.harness.HarnessHost;
import com.superagent.task.harness.HarnessState;
import com.superagent.task.harness.HarnessState.Finding;
import com.superagent.task.harness.HarnessState.ReviewRound;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules of the review loop that don't need a model: telling one round's issue from the last round's, and when to
 * stop sending the work back and ask the user instead.
 */
final class ReviewLoop {

    private ReviewLoop() {
    }

    /** Fixes in a row the same issue may survive, and rounds in a row the count may not fall, before the loop stops. */
    static final int STUCK = 2;

    /**
     * Ids for this round's issues: the one the reviewer kept ({@code #3}) when it was in {@code previous}; else a
     * previous issue in the same file with a similar description; else a new one. Issues the reviewer agreed to drop
     * ({@link HarnessState#waived}) come back waived.
     */
    static List<Finding> identify(HarnessState s, List<Finding> found, List<Finding> previous) {
        List<Finding> out = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (Finding f : found) {
            String id = f.id() != null && previous.stream().anyMatch(p -> f.id().equals(p.id())) ? f.id() : null;
            if (id == null) {
                id = previous.stream().filter(p -> p.id() != null && !taken.contains(p.id()) && same(p, f))
                        .map(Finding::id).findFirst().orElse(null);
            }
            if (id == null) {
                id = String.valueOf(s.nextFindingId++);
            }
            taken.add(id);
            Finding g = f.withId(id);
            out.add(s.waived.contains(id) && g.blocking() ? g.withState("waived") : g);
        }
        return out;
    }

    /** The same file (any line) and a description that reads alike. */
    static boolean same(Finding a, Finding b) {
        return file(a.where()).equals(file(b.where())) && similarity(a.text(), b.text()) >= 0.5;
    }

    private static String file(String where) {
        if (where == null) {
            return "";
        }
        String w = where.strip().replace("`", "");
        int colon = w.indexOf(':');
        return (colon > 0 ? w.substring(0, colon) : w.split("\\s+")[0]).toLowerCase(Locale.ROOT);
    }

    /** Dice coefficient over character pairs, blind to spaces and punctuation. */
    static double similarity(String a, String b) {
        Set<String> x = pairs(a);
        Set<String> y = pairs(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> both = new HashSet<>(x);
        both.retainAll(y);
        return 2.0 * both.size() / (x.size() + y.size());
    }

    private static Set<String> pairs(String s) {
        String t = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。、；：！？（）「」]", "");
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    /**
     * Why the loop stops now, after a round that didn't pass; null to send the work back once more. {@code since}: the
     * rounds since the loop last started counting; {@code spent}: what the task has spent since then.
     */
    static String stop(List<ReviewRound> since, HarnessHost.ReviewLimits limits, BigDecimal spent) {
        List<ReviewRound> failing = since.stream().filter(r -> !r.passed()).toList();
        if (failing.size() > STUCK) {
            List<ReviewRound> last = failing.subList(failing.size() - STUCK - 1, failing.size());
            Set<String> stuck = blockingIds(last.get(0));
            last.forEach(r -> stuck.retainAll(blockingIds(r)));
            if (!stuck.isEmpty()) {
                String id = stuck.iterator().next();
                Finding f = last.get(last.size() - 1).findings().stream().filter(x -> id.equals(x.id())).findFirst().orElseThrow();
                return "同一个问题修了 " + STUCK + " 轮还在：" + tag(id) + " " + f.text();
            }
            boolean notFalling = true;
            for (int i = 1; i < last.size(); i++) {
                notFalling &= last.get(i).blocking() >= last.get(i - 1).blocking();
            }
            if (notFalling) {
                return "严重和一般问题的总数连续 " + STUCK + " 轮没有减少（"
                        + last.stream().map(r -> String.valueOf(r.blocking())).collect(Collectors.joining(" → ")) + "）";
            }
        }
        long rounds = since.stream().map(ReviewRound::round).distinct().count();
        if (rounds >= limits.maxRounds()) {
            return "已经审查了 " + rounds + " 轮，到了设置的上限（" + limits.maxRounds() + " 轮）";
        }
        if (spent.doubleValue() >= limits.maxSpend()) {
            return "这段审查和修复已花费 $" + spent.setScale(2, java.math.RoundingMode.HALF_UP)
                    + "，到了设置的费用上限（$" + limits.maxSpend() + "）";
        }
        return null;
    }

    private static Set<String> blockingIds(ReviewRound r) {
        return r.findings().stream().filter(Finding::blocking).map(Finding::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String tag(String id) {
        return id == null ? "" : id.startsWith("AC") ? "验收 " + id.substring(2) : "#" + id;
    }
}
