package com.superagent.task.harness.subtitle;

import com.superagent.core.media.TextTokens;
import com.superagent.task.harness.HarnessState.PlatformCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subtitle norms, checked by the platform: timings in order and within the audio, covering the speech, no empty
 * cue, a line at most {@value #MAX_LINE} wide (a Chinese character 1, a letter half) and two lines a cue, a cue at
 * most {@value #MAX_SECONDS} seconds, a space between Chinese and English, terms cased as the glossary has them.
 * {@link #tidy} fixes what it can by itself first; what is left goes back to a model.
 */
final class SubtitleChecks {

    static final double MAX_LINE = 20, MAX_SECONDS = 7;
    static final int MAX_LINES = 2;
    /** How far before the audio's end the last cue may end (a short silence or fade-out). */
    static final double END_SLACK = 2.0;
    private static final Pattern CJK_LATIN = Pattern.compile("(\\p{IsHan})([A-Za-z0-9])|([A-Za-z0-9])(\\p{IsHan})");

    private SubtitleChecks() {
    }

    /** {@code origin}: the aligned cue it came from, so a model's fix of it can be kept against that cue. */
    record Cue(int origin, double start, double end, String text) {
        double seconds() {
            return end - start;
        }
    }

    // --- fixing ---

    /** Spaced, terms cased, empty cues dropped, long ones cut, lines wrapped, overlaps removed, kept within the audio. */
    static List<Cue> tidy(List<Cue> cues, List<String> terms, double seconds) {
        List<Cue> out = new ArrayList<>();
        for (Cue c : cues) {
            String text = caseTerms(space(c.text().replace('\n', ' ').replaceAll("\\s+", " ").strip()), terms);
            if (!text.isEmpty()) {
                cut(new Cue(c.origin(), c.start(), c.end(), text), out);
            }
        }
        List<Cue> timed = new ArrayList<>();
        double prev = 0;
        for (Cue c : out) {
            double start = Math.max(c.start(), prev);
            double end = seconds > 0 ? Math.min(c.end(), seconds) : c.end();
            if (end <= start) {
                end = start + 0.01; // still visible as wrong: the timing check names it
            }
            timed.add(new Cue(c.origin(), start, end, wrap(c.text())));
            prev = end;
        }
        return timed;
    }

    /** Halves a cue that is too long to show or too wide for two lines, at the break nearest its middle, until it fits. */
    private static void cut(Cue c, List<Cue> into) {
        boolean tooLong = c.seconds() > MAX_SECONDS || TextTokens.width(c.text()) > MAX_LINE * MAX_LINES;
        int at = tooLong ? breakNear(c.text(), TextTokens.width(c.text()) / 2) : -1;
        if (at <= 0) {
            into.add(c);
            return;
        }
        String left = c.text().substring(0, at).strip(), right = c.text().substring(at).strip();
        double share = TextTokens.width(left) / (TextTokens.width(left) + TextTokens.width(right));
        double mid = c.start() + c.seconds() * share;
        cut(new Cue(c.origin(), c.start(), mid, left), into);
        cut(new Cue(c.origin(), mid, c.end(), right), into);
    }

    /** Two lines when one is too wide, broken nearest the middle. */
    static String wrap(String text) {
        double w = TextTokens.width(text);
        if (w <= MAX_LINE) {
            return text;
        }
        int at = breakNear(text, w / 2);
        return at <= 0 ? text : text.substring(0, at).strip() + "\n" + text.substring(at).strip();
    }

    /**
     * Where {@code text} may break nearest {@code width} into it: at a space, or between two Chinese characters, or
     * between Chinese and anything else; -1 when nowhere (one long word).
     */
    static int breakNear(String text, double width) {
        int best = -1;
        double bestDist = Double.MAX_VALUE, w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            w += TextTokens.width(text.substring(i, next));
            if (next < text.length()) {
                int after = text.codePointAt(next);
                boolean ok = text.charAt(next) == ' ' || TextTokens.cjk(cp) || TextTokens.cjk(after);
                // A break at a space reads best: it counts as a little nearer.
                double dist = Math.abs(w - width) - (text.charAt(next) == ' ' ? 0.75 : 0);
                if (ok && dist < bestDist && !(Character.isLetterOrDigit(cp) && Character.isLetterOrDigit(after)
                        && !TextTokens.cjk(cp) && !TextTokens.cjk(after))) {
                    bestDist = dist;
                    best = next;
                }
            }
            i = next;
        }
        return best;
    }

    static String space(String text) {
        String t = text;
        for (Matcher m = CJK_LATIN.matcher(t); m.find(); m = CJK_LATIN.matcher(t)) {
            t = m.group(1) != null ? t.substring(0, m.start()) + m.group(1) + " " + m.group(2) + t.substring(m.end())
                    : t.substring(0, m.start()) + m.group(3) + " " + m.group(4) + t.substring(m.end());
        }
        return t;
    }

    static String caseTerms(String text, List<String> terms) {
        String t = text;
        for (String term : terms) {
            if (term.chars().anyMatch(ch -> ch < 128 && Character.isLetter(ch))) {
                t = termPattern(term).matcher(t).replaceAll(Matcher.quoteReplacement(term));
            }
        }
        return t;
    }

    private static Pattern termPattern(String term) {
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(term) + "(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // --- checking ---

    /**
     * {@code speechStart}/{@code speechEnd}: where the skeleton heard speech; {@code seconds}: the audio's length (0:
     * unknown, then the speech's end stands in for it).
     */
    static List<PlatformCheck> check(List<Cue> cues, double speechStart, double speechEnd, double seconds, List<String> terms) {
        List<PlatformCheck> out = new ArrayList<>();
        List<Integer> timing = new ArrayList<>(), empty = new ArrayList<>(), lines = new ArrayList<>(), length = new ArrayList<>(),
                spacing = new ArrayList<>(), casing = new ArrayList<>();
        double prev = 0;
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            int n = i + 1;
            if (c.start() < 0 || c.end() <= c.start() || c.start() < prev - 1e-6) {
                timing.add(n);
            }
            prev = c.end();
            if (c.text().isBlank()) {
                empty.add(n);
            }
            String[] ls = c.text().split("\n");
            if (ls.length > MAX_LINES || java.util.Arrays.stream(ls).anyMatch(l -> TextTokens.width(l) > MAX_LINE)) {
                lines.add(n);
            }
            if (c.seconds() > MAX_SECONDS + 1e-6) {
                length.add(n);
            }
            if (CJK_LATIN.matcher(c.text()).find()) {
                spacing.add(n);
            }
            for (String term : terms) {
                Matcher m = termPattern(term).matcher(c.text());
                while (m.find()) {
                    if (!m.group().equals(term) && term.chars().anyMatch(ch -> ch < 128 && Character.isLetter(ch))) {
                        casing.add(n);
                    }
                }
            }
        }
        out.add(result("时间轴", timing, "时间码合法、前后不重叠"));
        // Against the audio's own length, not where the skeleton stopped hearing: a transcript cut off midway must fail.
        double last = cues.isEmpty() ? 0 : cues.get(cues.size() - 1).end();
        boolean covered = !cues.isEmpty() && cues.get(0).start() <= speechStart + 1e-3
                && (seconds > 0 ? last >= seconds - END_SLACK && last <= seconds + 0.5 : last >= speechEnd - 1e-3);
        out.add(new PlatformCheck("覆盖时长", covered, cues.isEmpty() ? "没有字幕" : String.format(Locale.ROOT,
                "字幕 %s → %s，语音 %s → %s，音频 %s", clock(cues.get(0).start()), clock(cues.get(cues.size() - 1).end()),
                clock(speechStart), clock(speechEnd), clock(seconds))));
        out.add(result("空字幕条", empty, "没有空字幕条"));
        out.add(result("断句", union(lines, length), "每行不超过 20 字、每条最多 2 行、单条不超过 7 秒"));
        out.add(result("中英文空格", spacing, "中英文之间都有空格"));
        out.add(result("术语大小写", casing, "英文术语的大小写和术语表一致"));
        return out;
    }

    /** The cues the line and length norms failed on (numbers from 1). */
    static List<Integer> unbroken(List<Cue> cues) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            String[] ls = c.text().split("\n");
            if (ls.length > MAX_LINES || c.seconds() > MAX_SECONDS + 1e-6
                    || java.util.Arrays.stream(ls).anyMatch(l -> TextTokens.width(l) > MAX_LINE)) {
                out.add(i);
            }
        }
        return out;
    }

    private static List<Integer> union(List<Integer> a, List<Integer> b) {
        java.util.TreeSet<Integer> all = new java.util.TreeSet<>(a);
        all.addAll(b);
        return new ArrayList<>(all);
    }

    private static PlatformCheck result(String name, List<Integer> bad, String ok) {
        if (bad.isEmpty()) {
            return new PlatformCheck(name, true, ok);
        }
        List<String> shown = bad.stream().distinct().limit(5).map(n -> "第 " + n + " 条").toList();
        return new PlatformCheck(name, false, String.join("、", shown) + (bad.stream().distinct().count() > 5
                ? " 等 " + bad.stream().distinct().count() + " 条" : "") + "不符合");
    }

    static String srt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            sb.append(i + 1).append('\n').append(time(c.start())).append(" --> ").append(time(c.end())).append('\n')
                    .append(c.text()).append("\n\n");
        }
        return sb.toString();
    }

    static String time(double seconds) {
        long ms = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    static String clock(double seconds) {
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }
}
