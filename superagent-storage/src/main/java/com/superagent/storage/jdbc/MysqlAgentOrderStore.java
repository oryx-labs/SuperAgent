package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentOrderStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MySQL sidebar order: self-creating table, upsert via REPLACE. */
public class MysqlAgentOrderStore implements AgentOrderStore {

    private final JdbcTemplate jdbc;

    public MysqlAgentOrderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, """
                CREATE TABLE IF NOT EXISTS agent_sidebar_order (
                  company_id VARCHAR(64)  NOT NULL,
                  user_id    VARCHAR(128) NOT NULL,
                  agent_id   VARCHAR(64)  NOT NULL,
                  position   INT          NOT NULL,
                  PRIMARY KEY (company_id, user_id, agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    @Override
    public Map<String, Integer> positions(String userId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT agent_id, position FROM agent_sidebar_order WHERE company_id = ? AND user_id = ?",
                rs -> { out.put(rs.getString("agent_id"), rs.getInt("position")); }, TenantSchema.company(), userId);
        return out;
    }

    @Override
    public void save(String userId, List<String> orderedAgentIds) {
        String company = TenantSchema.company();
        for (int i = 0; i < orderedAgentIds.size(); i++) {
            jdbc.update("REPLACE INTO agent_sidebar_order (company_id, user_id, agent_id, position) VALUES (?, ?, ?, ?)",
                    company, userId, orderedAgentIds.get(i), i);
        }
    }
}
