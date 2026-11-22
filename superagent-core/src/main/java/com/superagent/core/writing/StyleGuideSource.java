package com.superagent.core.writing;

import java.util.Optional;

/** The company's document style standard: the short guide for system prompts and the word list checks use. */
public interface StyleGuideSource {

    /** The guide to put in a system prompt (at most {@link #MAX_CHARS} characters); empty when switched off or missing. */
    Optional<String> compactGuide(boolean technical);

    /** The word list of the company's standard, or {@link StyleLexicon#FALLBACK}. */
    StyleLexicon lexicon();

    int MAX_CHARS = 2000;
    String ON_KEY = "doc-style.on", SKILL_KEY = "doc-style.skill";

    /** A light guess from titles and requests: does the document concern code, design or research? */
    static boolean looksTechnical(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.of("调研", "方案", "技术", "架构", "设计文档", "prd", "代码", "接口", "api", "选型", "评审", "benchmark")
                .anyMatch(t::contains);
    }
}
