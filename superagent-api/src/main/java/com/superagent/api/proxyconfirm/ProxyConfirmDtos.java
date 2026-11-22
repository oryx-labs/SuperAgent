package com.superagent.api.proxyconfirm;

import java.time.Instant;
import java.util.List;

public final class ProxyConfirmDtos {

    private ProxyConfirmDtos() {
    }

    /** One kind of thing that can be confirmed: {@code tier} 1 | 2 | 3; tier 3 is never {@code enabled} and can't be turned on. */
    public record TypeDto(String type, int tier, boolean enabled, boolean revocable) {
    }

    /** {@code inherited}: a project with no rule of its own shows the global one. */
    public record RuleDto(String projectId, boolean enabled, boolean inherited, List<TypeDto> types) {
    }

    public record SaveRule(boolean enabled, List<String> types) {
    }

    public record RecordDto(String id, String owner, String projectId, String type, int tier, String objectType, String objectId,
                            String reason, boolean revocable, String status, Instant at) {
    }
}
