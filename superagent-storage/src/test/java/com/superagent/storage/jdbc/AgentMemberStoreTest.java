package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentMember;
import com.superagent.core.chat.AgentType;
import com.superagent.core.chat.ChatMessage;
import com.superagent.core.chat.ChatSession;
import com.superagent.core.chat.MessageRole;
import com.superagent.core.chat.SessionStatus;
import com.superagent.core.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Sharing an agent: membership rows and authored chat messages, through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class AgentMemberStoreTest {

    @Test
    void membersCanJoinAndLeaveAndMessagesKeepTheirAuthor() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:member-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        MysqlAgentMemberStore members = new MysqlAgentMemberStore(jdbc);
        Instant now = Instant.now();
        members.save(new AgentMember("a1", "bob", "ada", true, now, now));
        members.save(new AgentMember("a1", "cat", "ada", true, now, now));
        assertThat(members.members("a1")).extracting(AgentMember::username).containsExactly("bob", "cat");
        assertThat(members.agentsOf("bob")).containsExactly("a1");

        members.save(new AgentMember("a1", "bob", "ada", false, now, now.plusSeconds(1))); // bob leaves
        assertThat(members.members("a1")).extracting(AgentMember::username).containsExactly("cat");
        assertThat(members.agentsOf("bob")).isEmpty();

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(members.members("a1")).isEmpty();
            members.save(new AgentMember("a1", "cat", "eve", false, now, now.plusSeconds(2))); // same key, other company
        });
        assertThat(members.members("a1")).extracting(AgentMember::username).containsExactly("cat");

        MysqlChatStore chats = new MysqlChatStore(jdbc);
        chats.createSession(new ChatSession("s1", "t", AgentType.CLAUDE, null, "/w", "a1", SessionStatus.ACTIVE, now, now));
        chats.appendMessage(new ChatMessage("m1", "s1", MessageRole.USER, "改配色", now, "cat"));
        chats.appendMessage(new ChatMessage("m2", "s1", MessageRole.ASSISTANT, "好的", now.plusSeconds(1)));
        assertThat(chats.listMessages("s1")).extracting(ChatMessage::author).containsExactly("cat", null);
    }
}
