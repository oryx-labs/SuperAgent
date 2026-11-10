package com.superagent.storage.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps every table company-scoped: its DDL has a {@code company_id} column, and the store that owns it reads the
 * current company from {@code TenantContext}. Only {@link #GLOBAL} tables are exempt.
 */
class TenantScopeGuardTest {

    private static final Set<String> GLOBAL = Set.of("company");

    private static final Pattern DDL = Pattern.compile("CREATE TABLE IF NOT EXISTS (?:\\w+\\.)?(\\w+)\\s*\\((.*?)\\)\\s*(ENGINE|$)",
            Pattern.DOTALL);

    @Test
    void everyTableIsScopedByCompany() throws IOException {
        Set<String> unscoped = new TreeSet<>();
        Set<String> scopedWithoutContext = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = DDL.matcher(src);
                while (m.find()) {
                    String table = m.group(1);
                    if (GLOBAL.contains(table)) {
                        continue;
                    }
                    if (!m.group(2).contains("company_id")) {
                        unscoped.add(table);
                    } else if (!usesTenantContext(f, src)) {
                        scopedWithoutContext.add(table + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertThat(unscoped).as("tables without company_id").isEmpty();
        assertThat(scopedWithoutContext).as("company-scoped tables whose store never reads TenantContext").isEmpty();
    }

    private static boolean readsCompany(String src) {
        return src.contains("TenantContext.require()") || src.contains("TenantSchema.company()");
    }

    /** The DDL may sit in a Mysql (or, for analytics tables, ClickHouse) subclass while the queries live in its Abstract base. */
    private static boolean usesTenantContext(Path f, String src) throws IOException {
        if (readsCompany(src)) {
            return true;
        }
        Matcher base = Pattern.compile("extends (AbstractJdbc\\w+)").matcher(src);
        if (base.find()) {
            Path parent = f.resolveSibling(base.group(1) + ".java");
            return Files.exists(parent) && readsCompany(Files.readString(parent));
        }
        return false;
    }
}
