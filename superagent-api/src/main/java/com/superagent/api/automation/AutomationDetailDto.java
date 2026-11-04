package com.superagent.api.automation;

import java.time.Instant;
import java.util.List;

/** An automation with its recent runs (newest first) and its next few scheduled firings. */
public record AutomationDetailDto(
        AutomationDto automation,
        List<AutomationRunDto> runs,
        List<Instant> upcoming) {
}
