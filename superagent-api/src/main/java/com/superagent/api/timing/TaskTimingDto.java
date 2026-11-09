package com.superagent.api.timing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A task's time from creation to its end (or now, while {@code open}), as consecutive segments: waiting before it
 * ran, each turn ({@code turn} broken down), and the gaps between turns.
 */
public record TaskTimingDto(Instant createdAt, Instant endedAt, boolean open, List<Segment> segments) {

    /** Before the first turn: the scheduler, choosing its model, waiting for a free session. */
    public static final String WAIT_START = "wait_start";
    /** Waiting for the user's reply (to a confirmation or a question). */
    public static final String WAIT_USER = "wait_user";
    /** A complex task between handing out its subtasks and summing up: waiting for them to finish. */
    public static final String WAIT_SUBTASKS = "wait_subtasks";
    /** Between turns, waiting to run again (a free session, a requeue after moving to a stronger model). */
    public static final String WAIT_QUEUE = "wait_queue";
    /** A turn still running. */
    public static final String RUNNING = "running";
    public static final String TURN = "turn";

    public record Segment(String kind, Instant startedAt, long ms, TurnTimingDto turn) {
    }

    public static Segment wait(String kind, Instant from, Instant to) {
        return new Segment(kind, from, Math.max(0, Duration.between(from, to).toMillis()), null);
    }

    public static Segment turn(TurnTimingDto t) {
        return new Segment(TURN, t.startedAt(), t.totalMs(), t);
    }
}
