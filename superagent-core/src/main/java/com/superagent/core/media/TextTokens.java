package com.superagent.core.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mixed Chinese and English text as subtitles compare it: each Chinese character a token, each English word (with
 * the digits, dots and hyphens inside it, as in GPT-4o or 3.5) a token; punctuation and spacing are not tokens.
 * Joining puts a space between words, and between Chinese and English, and where punctuation stood.
 */
public final class TextTokens {

    private TextTokens() {
    }

    /** {@code key}: what is compared (lower case); {@code pause}: punctuation stood right before it. */
    public record Token(String surface, String key, boolean cjk, boolean pause) {
    }

    public static List<Token> of(String text) {
        List<Token> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        boolean pause = false;
        int i = 0, n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            if (cjk(cp)) {
                String s = text.substring(i, i + len);
                out.add(new Token(s, s, true, pause && !out.isEmpty()));
                pause = false;
                i += len;
            } else if (Character.isLetterOrDigit(cp)) {
                int j = i;
                while (j < n) {
                    int c = text.codePointAt(j);
                    if (Character.isLetterOrDigit(c) && !cjk(c)) {
                        j += Character.charCount(c);
                    } else if ((c == '.' || c == '-' || c == '\'' || c == '_') && j + 1 < n
                            && Character.isLetterOrDigit(text.codePointAt(j + 1)) && !cjk(text.codePointAt(j + 1))) {
                        j++;
                    } else {
                        break;
                    }
                }
                String s = text.substring(i, j);
                out.add(new Token(s, s.toLowerCase(Locale.ROOT), false, pause && !out.isEmpty()));
                pause = false;
                i = j;
            } else {
                if (!Character.isWhitespace(cp)) {
                    pause = true;
                }
                i += len;
            }
        }
        return out;
    }

    public static String join(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        Token prev = null;
        for (Token t : tokens) {
            if (prev != null && (t.pause() || !(prev.cjk() && t.cjk()))) {
                sb.append(' ');
            }
            sb.append(t.surface());
            prev = t;
        }
        return sb.toString();
    }

    /** Two pieces of subtitle text one after the other: a space unless both sides are Chinese. */
    public static String concat(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        boolean joined = cjk(a.codePointBefore(a.length())) && cjk(b.codePointAt(0));
        return joined || a.endsWith(" ") || b.startsWith(" ") ? a + b : a + " " + b;
    }

    /** What two writings are compared by: their tokens' keys, so case, spacing and punctuation don't count. */
    public static String key(String text) {
        StringBuilder sb = new StringBuilder();
        for (Token t : of(text)) {
            sb.append(t.key()).append('\u0001');
        }
        return sb.toString();
    }

    /** Chinese, Japanese or Korean script. */
    public static boolean cjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL;
    }

    /** How wide a line is on screen: a Chinese character 1, anything else half. */
    public static double width(String line) {
        double w = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            w += cjk(cp) || Character.getType(cp) == Character.OTHER_PUNCTUATION && cp > 0x2000 ? 1 : 0.5;
            i += Character.charCount(cp);
        }
        return w;
    }
}
