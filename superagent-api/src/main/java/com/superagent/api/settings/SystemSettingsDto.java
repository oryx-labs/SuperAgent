package com.superagent.api.settings;

/**
 * Platform-wide defaults that apply to every user; read by everyone, written by admins.
 *
 * @param agentDefaultModel model every Agent chats with (a Claude CLI model id);
 *                          null means the CLI's own default
 * @param companyName       the company's full name, shown in the account menu
 * @param companyShortName  its short name, for tight spots
 * @param publicUrl         how people reach this server from outside (e.g. https://ai.company.com), for addresses
 *                          handed to other systems such as OAuth callbacks; null = follow the browser's address.
 *                          On a save, null keeps it and blank clears it
 */
public record SystemSettingsDto(String agentDefaultModel, String companyName, String companyShortName,
                                /** Whether people choose a tier instead of a model; null on a save keeps it. */
                                Boolean tiersEnabled,
                                /** The tier used where none was chosen; null on a save keeps it. */
                                String defaultTier,
                                String publicUrl) {

    public SystemSettingsDto(String agentDefaultModel, String companyName, String companyShortName) {
        this(agentDefaultModel, companyName, companyShortName, null, null, null);
    }
}
