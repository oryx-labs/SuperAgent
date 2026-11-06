package com.superagent.knowledge;

import com.superagent.api.knowledge.KnowledgeSpaceDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three layers: personal (own), team (members write, sub-teams read upward), company (admins write). */
class KnowledgeSpacesTest {

    @Test
    void teamsReadUpwardOnlyMembersWriteAndOnlyAdminsWriteTheCompanyLayer(@TempDir Path dir) {
        KnowledgeService knowledge = new KnowledgeService(dir.toString());
        KnowledgeSpaces spaces = TestOrg.spaces();

        assertThat(spaces.visible("bob")).extracting(KnowledgeSpaceDto::id)
                .containsExactly("personal", "team:技术中心/后端组", "team:技术中心", "company");
        assertThat(spaces.visible("bob")).filteredOn(s -> s.id().equals("team:技术中心"))
                .singleElement().satisfies(s -> assertThat(s.canWrite()).isFalse()); // the parent: read only

        // A member writes their team; the sub-team member may read the parent but not write it.
        knowledge.createFolder(spaces.resolve("dan", "team:技术中心", true), "规范");
        assertThat(knowledge.list(spaces.resolve("bob", "team:技术中心", false), "")).hasSize(1);
        assertThatThrownBy(() -> spaces.resolve("bob", "team:技术中心", true)).isInstanceOf(KnowledgeReadOnlyException.class);
        // ...and the parent's members don't see the sub-team at all.
        assertThatThrownBy(() -> spaces.resolve("dan", "team:技术中心/后端组", false))
                .isInstanceOf(KnowledgeNotFoundException.class);

        assertThatThrownBy(() -> spaces.resolve("bob", "company", true)).isInstanceOf(KnowledgeReadOnlyException.class);
        knowledge.createFolder(spaces.resolve("root", "company", true), "制度");
        assertThat(knowledge.list(spaces.resolve("bob", "company", false), "")).hasSize(1);

        // A parent team's tree never shows its sub-teams' content.
        knowledge.createFolder(spaces.resolve("bob", "team:技术中心/后端组", true), "接口");
        assertThat(knowledge.list(KnowledgeSpace.team("技术中心"), "")).extracting(e -> e.name()).containsExactly("规范");

    }
}
