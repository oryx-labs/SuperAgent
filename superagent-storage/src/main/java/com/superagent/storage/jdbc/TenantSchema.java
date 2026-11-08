package com.superagent.storage.jdbc;

import com.superagent.core.tenant.TenantContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creating company-scoped tables, and the company every store query is scoped to.
 *
 * <p>Tables made before companies existed have no {@code company_id}, and {@code CREATE TABLE IF NOT EXISTS}
 * would keep them as they are. Their data is not migrated (a deliberate decision): such a table is dropped and
 * made again from the current DDL.
 */
final class TenantSchema {

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)");

    private TenantSchema() {
    }

    /** The company the current query is for; fails when there is none (see {@link TenantContext#require()}). */
    static String company() {
        return TenantContext.require();
    }

    /** Runs {@code ddl}, first dropping its table if it is a legacy one without {@code company_id}. */
    static void create(JdbcTemplate jdbc, String ddl) {
        Matcher m = TABLE.matcher(ddl);
        if (m.find() && isLegacy(jdbc, m.group(1))) {
            jdbc.execute("DROP TABLE " + m.group(1));
        }
        jdbc.execute(ddl);
    }

    // Probing with queries rather than JDBC metadata works the same on MySQL, H2 and ClickHouse.
    private static boolean isLegacy(JdbcTemplate jdbc, String table) {
        try {
            jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1 = 0");
        } catch (DataAccessException noTable) {
            return false;
        }
        try {
            jdbc.queryForList("SELECT company_id FROM " + table + " WHERE 1 = 0");
            return false;
        } catch (DataAccessException noColumn) {
            return true;
        }
    }
}
