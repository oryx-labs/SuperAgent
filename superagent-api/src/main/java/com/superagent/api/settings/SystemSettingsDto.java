package com.superagent.api.settings;

/**
 * Platform-wide defaults that apply to every user; read by everyone, written by admins.
 *
 * @param agentDefaultModel the model every conversation runs on (the SuperAgent and its background too), and that
 *                          judges which model each task needs; null means the CLI's own default
 * @param companyName       the company's full name, shown in the account menu
 * @param companyShortName  its short name, for tight spots
 * @param publicUrl         how people reach this server from outside (e.g. https://ai.company.com), for addresses
 *                          handed to other systems such as OAuth callbacks; null = follow the browser's address.
 *                          On a save, null keeps it and blank clears it
 */
public record SystemSettingsDto(String agentDefaultModel, String companyName, String companyShortName, String publicUrl,
                                /**
                                 * Models conversations move to, in this order, when the default model can't run; null
                                 * on a save keeps them. Without any, the strongest other model that can run.
                                 */
                                java.util.List<String> backupModels) {

    public SystemSettingsDto(String agentDefaultModel, String companyName, String companyShortName) {
        this(agentDefaultModel, companyName, companyShortName, null, null);
    }

    public SystemSettingsDto(String agentDefaultModel, String companyName, String companyShortName, String publicUrl) {
        this(agentDefaultModel, companyName, companyShortName, publicUrl, null);
    }
}
