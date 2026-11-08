package com.superagent.core.usage;

/**
 * Who a model run is for, so its tokens land on the right person. {@code user} pays: the person whose work it was
 * (the task's creator, whoever sent the chat message, the SuperAgent's owner). The rest say what it was for; any may
 * be null.
 *
 * @param source one of the {@code SOURCE_*} constants
 */
public record UsageContext(String user, String source, String agentId, String taskId, String sessionId, String cli) {

    public static final String SOURCE_TASK = "task";
    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_SUPERAGENT = "superagent";
    public static final String SOURCE_BACKGROUND = "superagent_background";
    public static final String SOURCE_MODEL_CHOICE = "model_choice";
    public static final String SOURCE_MEMORY = "memory";
    public static final String SOURCE_FILE_TAGS = "file_tags";
    public static final String SOURCE_TASK_TAGS = "task_tags";
}
