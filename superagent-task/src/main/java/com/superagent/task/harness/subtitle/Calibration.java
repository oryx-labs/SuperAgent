package com.superagent.task.harness.subtitle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superagent.core.media.TextTokens;
import com.superagent.core.media.TranscriptAligner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settling the disputed spans: each text model gets the spans with what every transcript heard, the context either
 * side (fixed, never theirs to change), the matching slide and the terms, and answers a writing per span. Only a
 * majority of them makes a writing stand; otherwise the span is a doubt for a person, shown as the skeleton heard it.
 */
final class Calibration {

    static final int BATCH = 40;
    private static final int CONTEXT = 40, SLIDE_CHARS = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private Calibration() {
    }

    /** One disputed span as a model is asked about it. */
    record Ask(TranscriptAligner.Span span, int cue, double start, double end, String before, String after, Integer slide) {
    }

    /** Every disputed span with its context: the decided text of its cue before and after it, and the cues around. */
    static List<Ask> asks(SubtitleRecord r) {
        List<Ask> out = new ArrayList<>();
        List<TranscriptAligner.AlignedCue> cues = r.cues;
        for (int i = 0; i < cues.size(); i++) {
            TranscriptAligner.AlignedCue c = cues.get(i);
            for (int k = 0; k < c.spans().size(); k++) {
                TranscriptAligner.Span s = c.spans().get(k);
                if (!s.disputed()) {
                    continue;
                }
                String before = (i > 0 ? plain(cues.get(i - 1)) + " " : "") + plain(c.spans().subList(0, k));
                String after = plain(c.spans().subList(k + 1, c.spans().size())) + (i + 1 < cues.size() ? " " + plain(cues.get(i + 1)) : "");
                Integer slide = SlideMatcher.best(r.slides, before + " " + String.join(" ", s.versions()) + " " + after);
                out.add(new Ask(s, i, c.start(), c.end(), tail(before.strip()), head(after.strip()), slide));
            }
        }
        return out;
    }

    static String prompt(List<Ask> batch, SubtitleRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.single
                ? "你在校准一段音频（常是中英混说）的字幕。只有一个听写模型，下面每一条都还没有交叉确认，请逐条给出你认为正确的写法。\n"
                : "你在校准一段音频（常是中英混说）的字幕。下面每一处是几个听写模型听得不一样的片段，请给出你认为正确的写法。\n");
        sb.append("""
                规则：
                - 只写这一处片段本身的正确文字，不要带上前文或后文：前后文已经确定，不能改。
                - 原话转写：中英混说保持原样，不翻译、不润色、不补充没说的话，口头语也照原样保留。
                - 专有名词、英文术语、缩写、人名以术语表和资料为准；拿不准时选最可能的那份听写原文。
                - 这里本来没有话，就给空字符串。
                """);
        if (!r.terms.isEmpty()) {
            sb.append("\n术语表（写法 ← 常被误识别为）：\n");
            r.terms.stream().limit(200).forEach(t -> sb.append("- ").append(t.term())
                    .append(t.aliases().isEmpty() ? "" : " ← " + String.join("、", t.aliases())).append('\n'));
        }
        sb.append("\n片段：\n");
        for (Ask a : batch) {
            sb.append("\n### ").append(a.span().id()).append("（").append(SubtitleChecks.time(a.start())).append("）\n");
            sb.append("前文：").append(a.before().isEmpty() ? "（无）" : a.before()).append('\n');
            sb.append("后文：").append(a.after().isEmpty() ? "（无）" : a.after()).append('\n');
            sb.append("各份听写：\n");
            for (int v = 0; v < r.models.size(); v++) {
                sb.append("- ").append(r.models.get(v)).append("：「").append(a.span().versions().get(v)).append("」\n");
            }
            if (a.slide() != null) {
                SubtitleRecord.Slide s = r.slides.get(a.slide());
                String text = s.text().replaceAll("\\s+", " ");
                sb.append("对应资料（").append(s.file()).append(" 第 ").append(s.page()).append(" 页）：")
                        .append(text.length() > SLIDE_CHARS ? text.substring(0, SLIDE_CHARS) + "…" : text).append('\n');
            }
        }
        sb.append("\n只输出 JSON，不要别的文字：{\"fixes\":[{\"id\":\"").append(batch.get(0).span().id())
                .append("\",\"text\":\"正确的写法\"}]}，每个片段一条。");
        return sb.toString();
    }

    /** The writing per span id; ids it wasn't asked about are dropped (it may only touch the spans in question). */
    static Map<String, String> parse(String reply, Set<String> asked) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode root = json(reply);
        if (root == null) {
            return out;
        }
        JsonNode list = root.isArray() ? root : root.path("fixes");
        for (JsonNode f : list) {
            String id = f.path("id").asText("").strip();
            if (asked.contains(id) && f.has("text") && f.path("text").isTextual()) {
                out.putIfAbsent(id, f.path("text").asText().replaceAll("\\s+", " ").strip());
            }
        }
        return out;
    }

    /**
     * The decision on one span. {@code calibrated}: model → its writing, from those that answered; {@code asked}: how
     * many calibrators there were. One that gave no answer here (failed, unreadable, skipped it) counts as not agreeing:
     * with two or more, a writing stands only when more than half of all of them gave it. One alone stands only on a
     * writing some transcript had. Without any calibrator, a majority of the transcripts stands.
     */
    static SubtitleRecord.Decision decide(Ask a, List<String> models, int skeleton, Map<String, String> calibrated, int asked) {
        List<String> heard = a.span().versions();
        String fallback = heard.get(skeleton);
        String keep = "暂用 " + models.get(skeleton) + " 的写法";
        if (asked == 0) {
            Top top = top(heard);
            boolean adopted = top.count * 2 > heard.size();
            return decision(a, heard, calibrated, adopted ? top.text : fallback, adopted, adopted
                    ? "没有文字模型校准：" + top.count + "/" + heard.size() + " 份听写一致"
                    : "没有文字模型校准，各份听写也不一致：" + keep);
        }
        if (calibrated.isEmpty()) {
            return decision(a, heard, calibrated, fallback, false, asked + " 个校准模型都没有给出这一处的写法：" + keep);
        }
        Top top = top(new ArrayList<>(calibrated.values()));
        List<String> agreeing = calibrated.entrySet().stream()
                .filter(e -> TextTokens.key(e.getValue()).equals(TextTokens.key(top.text))).map(Map.Entry::getKey).toList();
        if (asked == 1) {
            String model = calibrated.keySet().iterator().next();
            boolean heardSo = heard.stream().anyMatch(h -> TextTokens.key(h).equals(TextTokens.key(top.text)));
            return heardSo
                    ? decision(a, heard, calibrated, top.text, true, "只有一个校准模型 " + model + "，它选了听写里有的写法")
                    : decision(a, heard, calibrated, fallback, false, "只有一个校准模型 " + model
                    + "，它的写法「" + top.text + "」没有哪份听写这样听到，要人确认；" + keep);
        }
        String silent = calibrated.size() < asked ? "（" + (asked - calibrated.size()) + " 个没有回答，算不同意）" : "";
        if (top.count * 2 > asked) {
            return decision(a, heard, calibrated, top.text, true,
                    top.count + "/" + asked + " 个校准模型一致（" + String.join("、", agreeing) + "）" + silent);
        }
        long kinds = calibrated.values().stream().map(TextTokens::key).distinct().count();
        return decision(a, heard, calibrated, fallback, false, asked + " 个校准模型里最多 " + top.count + " 个一致（"
                + kinds + " 种写法）" + silent + "，没有过半；" + keep);
    }

    private static SubtitleRecord.Decision decision(Ask a, List<String> heard, Map<String, String> calibrated, String text,
                                                    boolean adopted, String reason) {
        return new SubtitleRecord.Decision(a.span().id(), a.cue(), a.start(), a.end(), heard, new LinkedHashMap<>(calibrated),
                text, adopted, !adopted, reason, null, a.slide());
    }

    private record Top(String text, int count) {
    }

    /** The writing given most often (by its tokens), as first given; ties go to the earlier one. */
    private static Top top(List<String> writings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> first = new LinkedHashMap<>();
        for (String w : writings) {
            String k = TextTokens.key(w);
            counts.merge(k, 1, Integer::sum);
            first.putIfAbsent(k, w);
        }
        String best = null;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (best == null || e.getValue() > counts.get(best)) {
                best = e.getKey();
            }
        }
        return best == null ? new Top("", 0) : new Top(first.get(best), counts.get(best));
    }

    static JsonNode json(String reply) {
        if (reply == null) {
            return null;
        }
        int obj = reply.indexOf('{'), arr = reply.indexOf('[');
        int start = obj < 0 ? arr : arr < 0 ? obj : Math.min(obj, arr);
        if (start < 0) {
            return null;
        }
        char close = reply.charAt(start) == '{' ? '}' : ']';
        int end = reply.lastIndexOf(close);
        if (end <= start) {
            return null;
        }
        try {
            return JSON.readTree(reply.substring(start, end + 1));
        } catch (IOException e) {
            return null;
        }
    }

    private static String plain(TranscriptAligner.AlignedCue c) {
        return plain(c.spans());
    }

    private static String plain(List<TranscriptAligner.Span> spans) {
        String out = "";
        for (TranscriptAligner.Span s : spans) {
            out = TextTokens.concat(out, s.text());
        }
        return out;
    }

    private static String tail(String s) {
        return s.length() <= CONTEXT ? s : "…" + s.substring(s.length() - CONTEXT);
    }

    private static String head(String s) {
        return s.length() <= CONTEXT ? s : s.substring(0, CONTEXT) + "…";
    }
}
