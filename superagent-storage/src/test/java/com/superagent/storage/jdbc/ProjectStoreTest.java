package com.superagent.storage.jdbc;

import com.superagent.core.file.ProjectFolder;
import com.superagent.core.project.Project;
import com.superagent.core.project.ProjectBrief;
import com.superagent.core.project.ProjectMemory;
import com.superagent.core.project.ProjectRepo;
import com.superagent.core.project.ProjectItemKind;
import com.superagent.core.project.ProjectMember;
import com.superagent.core.project.ProjectStatus;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Projects, their members, and what is in them, through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class ProjectStoreTest {

    @Test
    void projectsKeepMembersAndItemsMoveBetweenThem() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:project-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        MysqlProjectStore projects = new MysqlProjectStore(jdbc);
        MysqlProjectItemStore items = new MysqlProjectItemStore(jdbc);
        Instant now = Instant.now();

        Project p = new Project("p1", "看板", null, "ada", ProjectStatus.ACTIVE, now, now, null);
        projects.save(p);
        projects.save(p.withStatus(ProjectStatus.ARCHIVED));
        assertThat(projects.find("p1")).get().satisfies(x -> {
            assertThat(x.archived()).isTrue();
            assertThat(x.archivedAt()).isNotNull();
            assertThat(x.description()).isNull();
        });

        projects.saveMember(new ProjectMember("p1", "ada", "ada", true, now, now));
        projects.saveMember(new ProjectMember("p1", "bob", "ada", true, now, now.plusSeconds(1)));
        projects.saveMember(new ProjectMember("p1", "bob", "ada", false, now, now.plusSeconds(2))); // bob leaves
        assertThat(projects.members("p1")).extracting(ProjectMember::username).containsExactly("ada");
        assertThat(projects.projectsOf("bob")).isEmpty();

        items.assign(ProjectItemKind.AGENT, "a1", "p1");
        items.assign(ProjectItemKind.AGENT, "a1", "p2"); // an agent is in one project at a time
        assertThat(items.projectOf(ProjectItemKind.AGENT, "a1")).contains("p2");
        assertThat(items.items(ProjectItemKind.AGENT, "p1")).isEmpty();
        items.unassign(ProjectItemKind.AGENT, "a1");
        assertThat(items.projectOf(ProjectItemKind.AGENT, "a1")).isEmpty();

        items.assign(ProjectItemKind.TASK, "t1", "p1");
        items.assign(ProjectItemKind.FILE, "t1", "p2"); // kinds don't collide
        assertThat(items.items(ProjectItemKind.TASK, "p1")).containsExactly("t1");
        assertThat(items.projectOf(ProjectItemKind.FILE, "t1")).contains("p2");

        // Another company sees none of it; the same item id there is its own row.
        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(projects.find("p1")).isEmpty();
            assertThat(projects.list()).isEmpty();
            assertThat(projects.members("p1")).isEmpty();
            assertThat(items.items(ProjectItemKind.TASK, "p1")).isEmpty();
            projects.save(new Project("p1", "别家", null, "eve", ProjectStatus.ACTIVE, now, now, null));
            items.assign(ProjectItemKind.TASK, "t1", "p9");
        });
        assertThat(projects.find("p1")).get().satisfies(x -> assertThat(x.name()).isEqualTo("看板"));
        assertThat(items.projectOf(ProjectItemKind.TASK, "t1")).contains("p1");
    }

    @Test
    void contextAndSharedFoldersStayInTheirCompany() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:projctx-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        MysqlProjectContextStore context = new MysqlProjectContextStore(jdbc);
        MysqlProjectFolderStore folders = new MysqlProjectFolderStore(jdbc);
        Instant now = Instant.now();

        context.saveRepo(new ProjectRepo("r2", "p1", "web", "git@x:web.git", "main", "pr", null, 1, now, now));
        context.saveRepo(new ProjectRepo("r1", "p1", "api", "/code/api", "main", "merge", "后端", 0, now, now));
        assertThat(context.repos("p1")).extracting(ProjectRepo::name).containsExactly("api", "web");
        context.saveBrief(new ProjectBrief("p1", "上线看板", null, null, null, "ada", now));
        ProjectMemory m = new ProjectMemory("m1", "p1", "用 UTC", "时间一律存 UTC", "a1", "s1", null, "ada", "ada", false, now, now);
        context.saveMemory(m);
        assertThat(context.memory("m1")).get().satisfies(x -> assertThat(x.agentId()).isEqualTo("a1"));
        context.saveMemory(m.asDeleted("bob"));
        assertThat(context.memories("p1")).isEmpty();
        context.saveMemory(new ProjectMemory("m2", "p1", "坑", null, null, null, "t1", "bob", "bob", false, now, now));

        folders.save(new ProjectFolder("f1", "p1", "设计", null, false, now, now));
        folders.place("p1", "file1", "f1", "user");
        folders.place("p2", "file1", "", "user"); // one file, two projects
        assertThat(folders.projectsOfFile("file1")).containsExactlyInAnyOrder("p1", "p2");
        folders.unplace("p2", "file1");
        assertThat(folders.placements("p1")).containsEntry("file1", "f1");
        assertThat(folders.projectsOfFile("file1")).containsExactly("p1");

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(context.repos("p1")).isEmpty();
            assertThat(context.brief("p1")).isEmpty();
            assertThat(context.memories("p1")).isEmpty();
            assertThat(folders.list("p1")).isEmpty();
            assertThat(folders.placements("p1")).isEmpty();
            assertThat(folders.projectsOfFile("file1")).isEmpty();
        });
        TenantContext.clear();
        assertThatThrownBy(() -> context.repos("p1")).isInstanceOf(IllegalStateException.class); // no company, no query
        assertThatThrownBy(() -> folders.placements("p1")).isInstanceOf(IllegalStateException.class);
    }
}
