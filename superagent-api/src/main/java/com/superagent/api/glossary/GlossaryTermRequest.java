package com.superagent.api.glossary;

import java.util.List;

/** {@code category}: name | term | abbr | person (anything else is kept as given). */
public record GlossaryTermRequest(String term, List<String> aliases, String category) {
}
