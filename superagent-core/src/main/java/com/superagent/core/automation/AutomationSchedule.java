package com.superagent.core.automation;

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Schedule math for automations, always in the automation's own time zone (so "09:00 daily"
 * stays 09:00 across DST). Cron is the usual 5-field form ({@code minute hour day month weekday});
 * Spring's parser wants a seconds field, which is pinned to 0 here. Invalid input throws
 * {@link IllegalArgumentException} with a message fit to show a user or an agent.
 */
public final class AutomationSchedule {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    public static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone: " + timezone);
        }
    }

    /** Validates a 5-field cron and returns it normalized (single spaces). */
    public static String normalizeCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("A recurring automation needs a cron schedule");
        }
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron must have 5 fields (minute hour day month weekday): " + cron);
        }
        String normalized = String.join(" ", fields);
        parse(normalized);
        return normalized;
    }

    /** The first firing strictly after {@code after}, or null if the cron never fires again. */
    public static Instant nextCron(String cron, String timezone, Instant after) {
        ZonedDateTime next = parse(cron).next(after.atZone(zone(timezone)));
        return next == null ? null : next.toInstant();
    }

    /** The next {@code count} firings after {@code after}, for previews. */
    public static List<Instant> upcoming(String cron, String timezone, Instant after, int count) {
        List<Instant> out = new ArrayList<>();
        Instant cursor = after;
        for (int i = 0; i < count; i++) {
            cursor = nextCron(cron, timezone, cursor);
            if (cursor == null) {
                break;
            }
            out.add(cursor);
        }
        return out;
    }

    /** A wall-clock time like {@code 2026-11-05T15:00} in {@code timezone}, as an instant. */
    public static Instant parseLocal(String localDateTime, String timezone) {
        if (localDateTime == null || localDateTime.isBlank()) {
            throw new IllegalArgumentException("A one-time automation needs a run time");
        }
        try {
            return LocalDateTime.parse(localDateTime.trim()).atZone(zone(timezone)).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Run time must look like 2026-11-05T15:00: " + localDateTime);
        }
    }

    private static CronExpression parse(String fiveField) {
        try {
            return CronExpression.parse("0 " + fiveField);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron \"" + fiveField + "\": " + e.getMessage());
        }
    }

    private AutomationSchedule() {
    }
}
