package com.superagent.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCharactersTest {

    private static Agent agent(String character, String persona) {
        Instant now = Instant.now();
        return new Agent("a1", "Ada", null, character, persona, null, AgentStatus.ACTIVE, "alice", AgentKind.STANDARD, null, now, now);
    }

    @Test
    void presetsHaveUniqueKeysAndPrompts() {
        assertThat(AgentCharacters.all()).hasSizeGreaterThanOrEqualTo(20)
                .allSatisfy(c -> assertThat(c.prompt()).isNotBlank());
        assertThat(new HashSet<>(AgentCharacters.all().stream().map(AgentCharacters.Character::key).toList()))
                .hasSize(AgentCharacters.all().size());
    }

    @Test
    void systemPromptCombinesNamePresetAndExtraInstructions() {
        String preset = AgentCharacters.find("minimalist").orElseThrow().prompt();

        assertThat(AgentCharacters.systemPrompt(agent("minimalist", "You own releases.")))
                .startsWith("Your name is Ada.")
                .contains(preset)
                .contains("Additional instructions:\nYou own releases.");
        // Custom: the persona text is the whole character.
        assertThat(AgentCharacters.systemPrompt(agent(null, "A grumpy pirate.")))
                .contains("A grumpy pirate.").doesNotContain("Additional instructions");
        assertThat(AgentCharacters.systemPrompt(agent(null, " "))).isNull();
    }
}
