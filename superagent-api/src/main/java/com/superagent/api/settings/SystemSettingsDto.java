package com.superagent.api.settings;

/**
 * Platform-wide defaults that apply to every user; read by everyone, written by admins.
 *
 * @param agentDefaultModel model every Agent chats with (a Claude CLI model id);
 *                          null means the CLI's own default
 */
public record SystemSettingsDto(String agentDefaultModel) {
}
