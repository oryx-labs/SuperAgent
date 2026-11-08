package com.superagent.core.tenant;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Per-company directories under a shared data root: {@code <root>/<companyId>}. Resolve at use time — services are
 * built with no company in context — so whatever one company writes or reads on disk stays under its own directory.
 */
public final class TenantPaths {

    /** A company id that is one plain path segment: no separators, no "." / "..". */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    private TenantPaths() {
    }

    /** {@code root}'s directory for the current company; fails when there is none. */
    public static Path of(Path root) {
        return of(root, TenantContext.require());
    }

    public static Path of(Path root, String companyId) {
        if (companyId == null || !SAFE.matcher(companyId).matches() || companyId.contains("..")) {
            throw new IllegalStateException("Company id is not a safe directory name: " + companyId);
        }
        return root.resolve(companyId);
    }
}
