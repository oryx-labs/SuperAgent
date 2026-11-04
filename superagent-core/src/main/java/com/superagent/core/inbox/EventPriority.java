package com.superagent.core.inbox;

/** How soon a received message gets attention — like a person triaging their inbox. */
public enum EventPriority {
    /** Urgent: handled right away. */
    P0,
    /** Important (DMs, mentions): summarized hourly. */
    P1,
    /** Routine group traffic: folded into the daily digest. */
    P2,
    /** Noise (bot notices, "ok", stickers): archived, never processed. */
    IGNORE
}
