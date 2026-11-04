package com.superagent.core.automation;

/** What a trigger does while the automation's previous run is still going. */
public enum OverlapPolicy {
    /** Record the trigger as skipped; don't start another run. */
    SKIP,
    /** Start another run alongside it. */
    PARALLEL
}
