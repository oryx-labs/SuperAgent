package com.superagent.task.harness.subtitle;

import com.superagent.core.media.TextTokens;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reference pages for the subtitles: which page a stretch of speech is about, and the terms written on them. */
final class SlideMatcher {

    /** Below this a page shares too little with the speech to be the one it is about. */
    static final int MIN_SCORE = 3;
    private static final Pattern WORD = Pattern.compile("(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9.+#-]*[A-Za-z0-9+#](?![A-Za-z0-9])");

    private SlideMatcher() {
    }

    /** The page sharing most with {@code text}: an English word counts 3, a pair of Chinese characters 1. */
    static Integer best(List<SubtitleRecord.Slide> slides, String text) {
        Set<String> words = keys(text);
        Integer best = null;
        int bestScore = MIN_SCORE - 1;
        for (int i = 0; i < slides.size(); i++) {
            int score = 0;
            for (String k : keys(slides.get(i).text())) {
                if (words.contains(k)) {
                    score += k.startsWith("w:") ? 3 : 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static Set<String> keys(String text) {
        Set<String> out = new HashSet<>();
        List<TextTokens.Token> tokens = TextTokens.of(text);
        for (int i = 0; i < tokens.size(); i++) {
            TextTokens.Token t = tokens.get(i);
            if (!t.cjk()) {
                if (t.key().length() > 1) {
                    out.add("w:" + t.key());
                }
            } else if (i + 1 < tokens.size() && tokens.get(i + 1).cjk()) {
                out.add("c:" + t.key() + tokens.get(i + 1).key());
            }
        }
        return out;
    }

    /**
     * English terms as decks write them: acronyms (RAG), mixed case (LoRA, PyTorch), with digits (GPT-4o). Plain
     * words (the, model, Today) are no terms. Term → the first page it is on.
     */
    static Map<String, SubtitleRecord.Slide> terms(List<SubtitleRecord.Slide> slides) {
        Map<String, SubtitleRecord.Slide> out = new LinkedHashMap<>();
        for (SubtitleRecord.Slide s : slides) {
            Matcher m = WORD.matcher(s.text());
            while (m.find()) {
                String w = m.group();
                boolean upperInside = w.substring(1).chars().anyMatch(Character::isUpperCase);
                boolean digit = w.chars().anyMatch(Character::isDigit);
                if ((upperInside || digit && w.chars().anyMatch(Character::isLetter)) && w.length() >= 2) {
                    out.putIfAbsent(w, s);
                }
            }
        }
        return out;
    }
}
