package com.superagent.api.inbox;

/** The user's data intake at a glance: sources set up / connected, messages received today and handled so far. */
public record InboxSummaryDto(int sources, int connected, int today, int handled) {
}
