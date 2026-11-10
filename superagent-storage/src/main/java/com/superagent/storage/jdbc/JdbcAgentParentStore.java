package com.superagent.storage.jdbc;

import com.superagent.core.agent.AgentParentStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** JDBC agent parents on MySQL: one row per agent, replaced on change and deleted when the parent is removed. */
public class JdbcAgentParentStore implements AgentParentStore {

    static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS agent_parent (
              company_id VARCHAR(64) NOT NULL,
              agent_id   VARCHAR(64) NOT NULL,
              parent_id  VARCHAR(64) NOT NULL,
              updated_at DATETIME(3) NOT NULL,
              PRIMARY KEY (company_id, agent_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;


    private final JdbcTemplate jdbc;

    public JdbcAgentParentStore(JdbcTemplate jdbc, String ddl) {
        this.jdbc = jdbc;
        TenantSchema.create(jdbc, ddl);
    }

    @Override
    public void set(String agentId, String parentId) {
        Timestamp now = Timestamp.from(Instant.now());
        String company = TenantSchema.company();
        if (parentId == null) {
            jdbc.update("DELETE FROM agent_parent WHERE company_id = ? AND agent_id = ?", company, agentId);
        } else {
            jdbc.update("REPLACE INTO agent_parent (company_id, agent_id, parent_id, updated_at) VALUES (?, ?, ?, ?)", company,
                    agentId, parentId, now);
        }
    }

    @Override
    public Optional<String> parentOf(String agentId) {
        return jdbc.queryForList("SELECT parent_id FROM agent_parent"
                        + " WHERE company_id = ? AND agent_id = ?", String.class, TenantSchema.company(), agentId)
                .stream().filter(p -> p != null && !p.isBlank()).findFirst();
    }

    @Override
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT agent_id, parent_id FROM agent_parent" + " WHERE company_id = ?",
                rs -> {
                    String parent = rs.getString(2);
                    if (parent != null && !parent.isBlank()) {
                        out.put(rs.getString(1), parent);
                    }
                }, TenantSchema.company());
        return out;
    }
}
