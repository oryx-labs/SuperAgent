package com.superagent.platform.connector;

/** Adds a Jira connector. {@code product}: jira-cloud | jira-dc; {@code siteUrl}: the company's Jira address (jira-dc only). */
public record JiraConnectorRequest(String name, String description, String product, String siteUrl, Boolean enabled) {
}
