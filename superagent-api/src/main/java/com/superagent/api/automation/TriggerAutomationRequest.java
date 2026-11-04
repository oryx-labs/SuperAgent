package com.superagent.api.automation;

/** Body of an API trigger; {@code input} (optional) is appended to the automation's prompt for this run. */
public record TriggerAutomationRequest(String input) {
}
