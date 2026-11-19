package com.superagent.glossary;

import com.superagent.api.glossary.GlossaryTermRequest;
import com.superagent.core.error.ForbiddenException;
import com.superagent.core.glossary.GlossaryTerm;
import com.superagent.core.project.ProjectAccess;
import com.superagent.core.tenant.TenantContext;
import com.superagent.storage.jdbc.MysqlGlossaryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two levels of terms; fixes and documents teach them; each company sees only its own. */
class GlossaryServiceTest {

    private GlossaryService service;
    private final ProjectAccess projects = mock(ProjectAccess.class);

    @BeforeEach
    void setUp() {
        TenantContext.set("acme");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:glossary-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        service = new GlossaryService(new MysqlGlossaryStore(jdbc));
        service.setProjects(projects);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aFixTeachesTheProjectsListWhichStandsOverTheCompanys() {
        service.create(null, new GlossaryTermRequest("Transformer", List.of("穿死佛么"), "term"), "ada", true);
        service.addFound("p1", List.of(new GlossaryService.Found("LoRA", "abbr", "第02课.pptx 第 14 页"),
                new GlossaryService.Found("transformer", "term", "第02课.pptx 第 3 页")), "ada");

        // What changed between what was heard and the fix: the term, and what it was misheard as.
        assertThat(service.learn("p1", "我们用 Laura 微调", "我们用 LoRA 微调", "ada")).get()
                .satisfies(t -> assertThat(t.aliases()).containsExactly("Laura"));
        service.learn("p1", "这里的 transformer 很关键", "这里的 Transformer 很关键", "ada");
        assertThat(service.learn("p1", "完全一样", "完全一样", "ada")).isEmpty();

        List<GlossaryTerm> merged = service.merged("p1");
        assertThat(merged).extracting(GlossaryTerm::term).containsExactlyInAnyOrder("Transformer", "LoRA");
        assertThat(merged).filteredOn(t -> t.term().equals("LoRA")).singleElement().satisfies(t -> {
            assertThat(t.projectId()).isEqualTo("p1");
            assertThat(t.source()).isEqualTo(GlossaryTerm.DOCUMENT);
            assertThat(t.sourceRef()).isEqualTo("第02课.pptx 第 14 页");
        });
        assertThat(merged).filteredOn(t -> t.term().equals("Transformer")).singleElement()
                .satisfies(t -> assertThat(t.aliases()).contains("穿死佛么")); // the company's mishearings come along
        assertThat(service.merged(null)).extracting(GlossaryTerm::term).containsExactly("Transformer");
    }

    @Test
    void onlyAdminsChangeTheCompanysListAndOnlyMembersAProjectsAndCompaniesStayApart() {
        assertThatThrownBy(() -> service.create(null, new GlossaryTermRequest("RAG", null, null), "bob", false))
                .isInstanceOf(ForbiddenException.class);
        doThrow(new ForbiddenException("no")).when(projects).requireWritable("p1", "eve");
        assertThatThrownBy(() -> service.create("p1", new GlossaryTermRequest("RAG", null, null), "eve", false))
                .isInstanceOf(ForbiddenException.class);
        when(projects.member("p1", "ada")).thenReturn(true);
        service.create("p1", new GlossaryTermRequest("RAG", List.of("rag", "瑞格"), "abbr"), "ada", false);
        assertThat(service.list("p1", "ada")).singleElement().satisfies(t -> assertThat(t.aliases()).containsExactly("瑞格"));

        TenantContext.run("beta", () -> {
            assertThat(service.merged("p1")).isEmpty();
            assertThat(service.learn("p1", "瑞格", "RAG", "eve")).isPresent();
        });
        assertThat(service.list("p1", "ada")).singleElement().satisfies(t -> assertThat(t.aliases()).containsExactly("瑞格"));
        TenantContext.clear();
        assertThatThrownBy(() -> service.merged(null)).isInstanceOf(IllegalStateException.class); // no company, no query
    }
}
