package com.superagent.core.usage;

import java.time.Instant;
import java.util.List;

/** Token usage records, kept as they come; read back by task or by time. */
public interface UsageStore {

    void save(UsageRecord record);

    /**
     * The newest chat or SuperAgent record of a session (this company's), or null: the largest context a single
     * request of its last turn read ({@link UsageRecord#contextTokens()}), not the turn's sum over requests.
     */
    UsageRecord latestChatTurn(String sessionId);

    /** A task's records, oldest first. */
    List<UsageRecord> forTask(String taskId);

    /** Records from {@code from} (inclusive) to {@code to} (exclusive), oldest first; {@code user} null = everyone's. */
    List<UsageRecord> between(String user, Instant from, Instant to);
}
