package com.superagent.web.mcp;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A per-process shared secret gating the local {@code /mcp} endpoint. The chat
 * agent receives it in its MCP config headers; {@link McpController} checks it.
 * Regenerated each boot — the agent is spawned by this process, so it always has
 * the current value.
 */
@Component
public class McpToken {

    private final String value;

    public McpToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        this.value = HexFormat.of().formatHex(bytes);
    }

    public String value() {
        return value;
    }
}
