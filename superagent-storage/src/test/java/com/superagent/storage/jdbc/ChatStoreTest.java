package com.superagent.storage.jdbc;

import com.superagent.core.chat.AgentEvent;
import com.superagent.core.chat.AgentEventType;
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

/** Sessions, messages and events stay within their company, through H2 in MySQL mode. */
@ExtendWith(InCompany.class)
class ChatStoreTest {

    @Test
    void anotherCompanyNeitherSeesNorChangesAConversation() {
        MysqlChatStore store = new MysqlChatStore(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chat-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")));
        Instant now = Instant.now();
        ChatSession session = new ChatSession("s1", "mine", AgentType.CLAUDE, null, "/w", "a1", SessionStatus.ACTIVE, now, now);
        store.createSession(session);
        store.appendMessage(new ChatMessage("m1", "s1", MessageRole.USER, "hello", now, "ada"));
        store.appendEvent(new AgentEvent("s1", "m1", 1, AgentEventType.TEXT, "{}", now));

        TenantContext.run(InCompany.OTHER, () -> {
            assertThat(store.findSession("s1")).isEmpty();
            assertThat(store.listSessions()).isEmpty();
            assertThat(store.listSessionsByAgent("a1")).isEmpty();
            assertThat(store.listMessages("s1")).isEmpty();
            assertThat(store.listEvents("s1")).isEmpty();
            store.updateSession(new ChatSession("s1", "theirs", AgentType.CLAUDE, null, "/w", "a1",
                    SessionStatus.ACTIVE, now, now.plusSeconds(1)));
            store.updateMessage("s1", "m1", "changed");
        });
        assertThat(store.findSession("s1")).hasValueSatisfying(s -> assertThat(s.title()).isEqualTo("mine"));
        assertThat(store.listMessages("s1")).extracting(ChatMessage::content).containsExactly("hello");
        assertThat(store.listEvents("s1")).hasSize(1);
    }
}
